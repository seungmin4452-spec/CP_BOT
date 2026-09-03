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
- [x] **Phase 2: 문서 Ingestion 파이프라인** - `POST /api/documents` (PDF 업로드 → 페이지 분리 → Chunking → Gemini 임베딩 → ES bulk 적재)
- [ ] **Phase 3: 하이브리드 검색 및 RBAC 필터링** - ES RRF retriever(BM25 + kNN) + 사전 권한 필터
- [ ] **Phase 4: RAG 채팅 API** - Spring AI `QuestionAnswerAdvisor`/`RetrievalAugmentationAdvisor` 기반 프롬프트 조합

### Phase 2 설계 메모

- PDF 파싱은 (원래 계획했던 raw PDFBox 대신) Spring AI의 `spring-ai-pdf-document-reader`
  (`PagePdfDocumentReader` + `TokenTextSplitter`, 패키지 `org.springframework.ai.reader.pdf` /
  `org.springframework.ai.transformer.splitter`)를 사용한다. Spring AI ETL 파이프라인과 메타데이터
  처리(페이지 번호 등)를 그대로 활용할 수 있어 raw PDFBox보다 적은 코드로 동일한 결과를 얻는다.
- **`Document.metadata`는 String/int/float/boolean만 허용**하고 배열을 지원하지 않는다(Spring AI 공식 문서).
  그래서 RBAC용 `allowed_roles`(List)는 `Document`에 담지 않고, `DocumentIngestionService`가
  Elasticsearch로 보낼 최종 `Map<String,Object>`을 조립하는 시점에 직접 추가한다
  (`DocumentIngestionService.toElasticsearchDocument()` 참고). `vectorStore.add()`를 쓰지 않고
  `EmbeddingModel.embed()` + `ElasticsearchClient.bulk()`로 직접 색인하는 이유이기도 하다 -
  Phase 3의 RRF 하이브리드 검색도 결국 저수준 클라이언트가 필요해서 read/write 경로가 일관된다.
- ES 인덱스는 `initialize-schema: false`로 자동 생성을 끄고, `ElasticsearchIndexInitializer`
  (`ApplicationRunner`)가 `elasticsearch/company-policy-index-mapping.json`으로 커스텀 매핑을 생성한다.
  이 매핑에는 한국어 BM25 품질을 위한 `nori` 분석기(`korean_nori_analyzer`)가 포함되어 있고,
  이를 위해 `docker/elasticsearch/Dockerfile`에서 `analysis-nori` 플러그인을 설치한 커스텀 ES 이미지를 빌드한다
  (`docker-compose.yml`의 `elasticsearch` 서비스가 `image:` 대신 `build:`를 사용하도록 변경됨).
- `dense_vector.dims`(매핑 JSON, 현재 768)와 `application.yml`의 `spring.ai.google.genai.embedding.text.dimensions` /
  `spring.ai.vectorstore.elasticsearch.dimensions` 세 값은 반드시 동일해야 한다. 하나라도 바꾸면 셋 다 같이 바꿀 것.
- **`SecurityConfig`는 임시로 모든 요청을 허용**한다 (`spring-boot-starter-security`가 있으면 기본적으로
  전 요청이 인증을 요구해서 로컬 테스트가 불가능하기 때문). Phase 3에서 실제 인증/인가로 반드시 교체해야 하며,
  이 상태로 배포하면 안 된다.
- `server.error.include-stacktrace: never`를 설정해도 **로컬 `bootRun`에서는 스택트레이스가 계속 노출된다.**
  `spring-boot-devtools`(developmentOnly 의존성)가 개발 편의를 위해 이 값을 강제로 `always`로 덮어쓰기 때문이며,
  devtools가 빠지는 실제 운영 빌드(`bootJar`)에서는 설정한 대로 `never`가 적용된다. 버그 아님 - 재확인하느라 시간 쓰지 말 것.
- Gemini 임베딩은 현재 `task-type: RETRIEVAL_DOCUMENT`로 전역 고정되어 있다. 이는 문서 적재(비대칭 임베딩의
  document 쪽)에는 맞지만, Phase 4에서 사용자 질문을 임베딩할 때는 `RETRIEVAL_QUERY`가 검색 정확도상 더 적합하다.
  Phase 4에서 질문 임베딩용 별도 설정/호출 경로가 필요한지 검토할 것.

### Phase 2 사용법 (로컬)

```bash
curl -X POST http://localhost:8080/api/documents \
  -F "file=@/path/to/규정.pdf" \
  -F "documentTitle=휴가 규정" \
  -F "category=인사" \
  -F "allowedRoles=USER" -F "allowedRoles=ADMIN"
```

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
