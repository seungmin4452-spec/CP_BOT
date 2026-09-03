# CP_BOT - 사내 규정 Q&A RAG 챗봇

Spring Boot + Elasticsearch + Gemini 기반 RAG(검색 증강 생성) 챗봇. 사내 규정 PDF를 임베딩하여
Elasticsearch에 적재하고, 하이브리드 검색(BM25 + Vector) 및 RBAC 권한 필터링을 거쳐 Gemini가
출처를 명시한 답변을 생성한다.

## 기술 스택 (버전 고정 - 임의로 올리거나 내리지 말 것)

| 영역 | 선택 | 비고 |
|---|---|---|
| Language | Java 25 (LTS) | Gradle toolchain으로 고정 |
| Framework | Spring Boot 4.1.1 | Spring Framework 7 기반 |
| AI Orchestration | Spring AI 2.0.1 (BOM) | 1.x 대비 패키지/프로퍼티 이름이 다수 변경됨 |
| Vector/Search DB | Elasticsearch 9.5.0 (Docker) | `docker-compose.yml` 참고 |
| Chat Model | `gemini-3.5-flash` | 필요 시 정확도 우선 `gemini-2.5-pro`로 교체 |
| Embedding Model | `gemini-embedding-001` | `text-embedding-004`는 2026-01-14 폐기(deprecated)됨. 절대 사용 금지 |
| Security | Spring Security (RBAC) | 인가는 반드시 ES 쿼리 레벨 pre-filter로 처리 |

## ⚠️ 이 프로젝트에서 특히 주의할 점: AI 생태계 버전 churn

Gemini 모델명, Spring AI 프로퍼티 키, ES 하이브리드 검색 문법은 수개월 단위로 바뀐다.
**이미 한 번 폐기/개명이 확인된 사례:**
- `text-embedding-004` → `gemini-embedding-001` (2026-01-14 폐기)
- `spring-ai-starter-model-google-genai` (chat)와 `spring-ai-starter-model-google-genai-embedding` (embedding)은
  **별도 스타터/별도 프로퍼티 네임스페이스**다. `spring.ai.google.genai.api-key`(chat)와
  `spring.ai.google.genai.embedding.api-key`(embedding)를 각각 채워야 한다. 하나만 설정하면 다른 쪽이 조용히 실패한다.
- Gemini 2.5 Flash/Pro 계열은 2026-10-16(Dev API 기준) 종료 예정이므로 신규 코드에 도입하지 말 것.

**따라서:** 모델명, 의존성 좌표, application.yml 프로퍼티 키를 코드에 새로 추가하기 전에
Spring AI 공식 레퍼런스(`docs.spring.io/spring-ai/reference`)와 Gemini 모델 페이지(`ai.google.dev/gemini-api/docs/models`)를
WebSearch/WebFetch로 재확인한다. 기억이나 과거 세션의 값을 그대로 믿지 않는다.

## 단계별 개발 계획 및 현재 상태

- [x] **Phase 1: 인프라/프로젝트 세팅** - `docker-compose.yml`, `build.gradle`, `application.yml` 완료
- [ ] **Phase 2: 문서 Ingestion 파이프라인** - PDF 파싱(Apache PDFBox 예정) → Chunking → 임베딩 → ES 적재
- [ ] **Phase 3: 하이브리드 검색 및 RBAC 필터링** - ES RRF retriever(BM25 + kNN) + 사전 권한 필터
- [ ] **Phase 4: RAG 채팅 API** - Spring AI `QuestionAnswerAdvisor`/`RetrievalAugmentationAdvisor` 기반 프롬프트 조합

각 Phase는 사용자가 명시적으로 다음 단계를 요청할 때만 진행한다. 여러 Phase를 한 번에 앞서 구현하지 않는다.

## 로컬 실행

```bash
# 1. Elasticsearch(+Kibana) 기동
docker compose up -d

# 2. .env.example을 .env로 복사 후 GEMINI_API_KEY 채우기 (또는 IntelliJ Run Config 환경변수로 주입)
cp .env.example .env

# 3. 애플리케이션 실행 (환경변수는 셸에서 export 하거나 IDE에서 주입)
./gradlew bootRun
```

- ES 상태 확인: `curl http://localhost:9200/_cluster/health`
- Kibana: http://localhost:5601

## 핵심 설계 원칙

1. **인가는 항상 쿼리 레벨 pre-filter로.** 검색 결과를 받은 뒤 애플리케이션 코드에서 필터링(post-filter)하지 않는다.
   문서를 볼 권한이 없는 사용자에게는 애초에 ES가 해당 document를 반환하지 않아야 한다 (`terms` 필터를 kNN/BM25 쿼리에 결합).
2. **환각 억제.** 시스템 프롬프트는 항상 "제공된 컨텍스트에 없는 내용은 추측하지 말고 '알 수 없습니다'라고 답하라"를 포함한다.
3. **출처 표기 필수.** 답변 끝에는 반드시 근거 문서명 + 페이지 번호를 명시한다. 이 메타데이터는 Ingestion 단계에서
   Elasticsearch 문서에 함께 저장돼 있어야 하며, 답변 생성 코드는 이를 누락 없이 노출해야 한다.
4. **API Key는 절대 하드코딩/커밋 금지.** `application.yml`은 항상 `${GEMINI_API_KEY}` 같은 플레이스홀더만 사용한다.
   `.env`는 `.gitignore`에 포함되어 있다.

## 코딩 컨벤션

- 주석은 한국어로, **"왜"가 비자명한 경우에만** 작성한다 (무엇을 하는지는 코드/네이밍으로 표현). 함수 단위 장문 Javadoc은 지양.
- 요청 범위를 벗어난 리팩토링/추상화를 함께 밀어넣지 않는다. 각 Phase가 요구하는 만큼만 구현한다.
- 실무 수준 예외 처리를 하되, 발생할 수 없는 상황에 대한 방어 코드는 추가하지 않는다 (예: Ingestion 파이프라인 내부 호출에 대한 과도한 null 체크).
- Elasticsearch 인덱스 매핑은 항상 명시적 JSON/Java 코드로 보여주고, `dense_vector`의 `dims`가
  `application.yml`의 embedding dimensions(현재 768)와 실제로 일치하는지 매 Phase마다 재확인한다.
