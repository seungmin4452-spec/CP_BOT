# 성능 개선 기록

RAG 파이프라인 성능(지연시간/검색 품질) 개선 작업을 시간순으로 기록한다. Phase별 기능 설계는
`CLAUDE.md`에 남기고, 이 문서는 "기능은 그대로인데 더 빠르게/더 정확하게 만든" 순수 성능 개선만 모은다.

## 2026-09-10: BM25/kNN 검색 병렬 실행

**무엇을 바꿨나** (`HybridSearchService.search()`)

기존에는 아래 세 번의 네트워크 왕복이 전부 순차로 실행됐다.

```
질의 임베딩(Gemini) → BM25 검색(ES) → kNN 검색(ES)
```

BM25 검색은 질의 임베딩과 아무 의존관계가 없다(텍스트 매치라 벡터가 필요 없음). 따라서
"BM25 검색"과 "질의 임베딩 → kNN 검색"을 `CompletableFuture`로 동시에 실행하도록 바꿨다.

```
BM25 검색(ES)              ─┐
질의 임베딩(Gemini) → kNN 검색(ES) ─┴→ RRF 융합
```

- 두 작업 모두 블로킹 I/O(ES/Gemini API 호출)라, 요청마다 스레드를 새로 띄워도 비용이 거의 없는
  가상 스레드(`Executors.newVirtualThreadPerTaskExecutor()`, Java 25)로 실행했다. 이 실행기는
  `HybridSearchService` 안에서만 쓰는 로컬 스레드풀이라 애플리케이션 전역 스레드 모델(Tomcat 등)에는
  영향을 주지 않는다.
- `CompletableFuture.join()`은 예외를 `CompletionException`으로 감싸는데, 그대로 두면
  `runSearch()`가 던지는 `IllegalStateException`을 `GlobalExceptionHandler`가 더 이상 인식하지 못해
  메시지 없는 500으로 뭉개진다. `HybridSearchService.join()` 헬퍼가 `CompletionException`의 원인이
  `RuntimeException`이면 그대로 다시 던져서, 병렬화 이전과 동일한 예외 처리 경로(400/500 매핑)를 유지한다.

**효과**

전체 검색 지연시간이 "임베딩 + BM25 + kNN" 순차 합산에서 `max(BM25, 임베딩 + kNN)`으로 줄어든다.
세 호출이 비슷한 시간이 걸린다고 가정하면 이론상 약 1/3 수준으로 단축된다(실측은 운영 ES/Gemini API
응답시간에 따라 달라짐 - 별도 부하 테스트로 확인 필요).

**검증**

`JAVA_HOME`을 JDK 21로 두고(Gradle 자체 구동용) `./gradlew compileJava`로 컴파일 확인만 했다
(로컬에 ES/Gemini API 키가 준비된 환경에서의 end-to-end 재검증은 별도로 필요). 기존 RBAC 필터
로직(`buildBm25Body`/`buildKnnBody`), RRF 융합 공식(`fuseWithRrf`)은 변경하지 않았다.

**리스크/트레이드오프**

- 요청 하나당 검색 단계에서 가상 스레드 2개(BM25, 임베딩→kNN)를 추가로 쓴다. 가상 스레드는 OS
  스레드를 점유하지 않으므로 동시 요청이 늘어나도 스레드 고갈 문제는 없다.
- 두 결과 모두 `runSearch()`가 실패하면 첫 예외가 그대로 전파된다(기존과 동일하게 "부분 성공"은
  지원하지 않음 - RBAC 필터가 한쪽 쿼리에서만 빠지는 것을 방지하려면 두 쿼리 모두 성공해야 하므로
  의도된 동작).

## 백로그 (아직 미착수)

아래는 같은 검토에서 함께 제안했지만 아직 구현하지 않은 항목이다. 우선순위/트레이드오프는
제안 시점 논의 내용을 요약한 것으로, 착수 전 재확인이 필요하다.

| # | 항목 | 비고 |
|---|---|---|
| 2 | 답변 스트리밍(`ChatClient.stream()` + SSE) | 체감 응답속도 개선, `chat.js` 렌더링 변경 필요 |
| 3 | `spring.threads.virtual.enabled: true` (앱 전역) | 설정 한 줄, ES/Gemini 블로킹 호출 처리량 개선 |
| 4 | RRF 융합 시 문서 다양성(중복 제거) | 같은 문서 인접 조항 중복 선택 방지 |
| 5 | BM25/kNN 가중치 튜닝 | `RANK_CONSTANT` 및 가중치 실험 필요 |
| 6 | 재랭킹(rerank) 단계 추가 | 답변 근거 품질 개선, 지연시간/비용 트레이드오프 있음 |
| 7 | Gemini 임베딩 `taskType`(RETRIEVAL_QUERY/DOCUMENT) 분리 | spring-ai#5966 이슈가 해결됐는지 공식 문서로 먼저 재확인 필요 |
| 8 | 표/이미지 문서 처리(테이블 재구성, OCR) | 범위가 커서 별도 논의 후 진행 |
