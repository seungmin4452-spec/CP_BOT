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
- [x] **Phase 3: 하이브리드 검색 및 RBAC 필터링** - `POST /api/search` (ES RRF retriever(BM25 + kNN) + 사전 권한 필터)
- [x] **Phase 4: RAG 채팅 API** - `POST /api/chat` (검색 결과 기반 프롬프트 조합 + Gemini 응답 + 출처 표기)

### Phase 4 설계 메모

- Spring AI의 `QuestionAnswerAdvisor`/`RetrievalAugmentationAdvisor`는 내부적으로 `VectorStore`를 호출하는
  구조인데, 이 프로젝트는 Phase 2/3에서 이유가 있어 `VectorStore` 자동설정을 껐다(위 Phase 2/3 메모 참고).
  그래서 Advisor를 쓰지 않고 `RagChatService`가 `HybridSearchService` 결과로 프롬프트를 직접 조립해
  `ChatClient`(Spring AI가 `ChatModel` 존재 시 자동 구성하는 `ChatClient.Builder` 빈을 주입받아 build)를 호출한다.
- **검색 결과가 0건이면 Gemini Chat 호출 자체를 생략**하고 고정 문구("제공된 사내 규정 문서에서 관련 내용을
  찾을 수 없습니다.")를 즉시 반환한다(`RagChatService.ask()`). 다만 질의 임베딩(kNN 검색을 위한
  `EmbeddingModel.embed()`)은 검색 자체에 필요해서 결과가 0건이어도 항상 호출된다 - 아낄 수 있는 건
  Chat Completion 호출뿐이다.
- **RBAC는 검색 단계에서 이미 차단되므로, LLM은 애초에 권한 없는 문서 내용을 보지 못한다** (프롬프트로
  "이건 보지 마"라고 지시하는 방식이 아니라 컨텍스트에 아예 포함되지 않음). 2026-09-03 실제 Gemini API 키로
  end-to-end 검증: USER 역할로 ADMIN 전용 문서(임원 성과급 규정) 내용을 질문하면 "찾을 수 없습니다"를 반환했고,
  같은 질문을 ADMIN으로 하면 정확한 답변 + 출처(`문서명 (파일명, p.페이지)`)를 반환했다.
- 시스템 프롬프트는 (1) 컨텍스트에 없으면 추측 금지 및 고정 문구로만 답변, (2) 답변 마지막 줄에 실제 활용한
  출처를 반드시 명시하도록 지시한다. 이와 별개로 검색 메타데이터에서 뽑은 **구조화된 `citations` 배열도
  응답에 항상 포함**한다 - LLM이 출처 표기를 깜빡하거나 형식을 틀려도 프런트엔드가 신뢰할 수 있는 출처를
  보여줄 수 있도록 이중 안전장치를 둔 것.

### ⚠️ 로컬 검증 중 발견한 환경 문제 (재발 방지용 기록)

- **`.env`에 값이 빈 채로 남은 줄(`APP_SECURITY_DEMO_ADMIN_PASSWORD=`)이 있으면, `set -a; source .env`로
  내보낼 때 "빈 문자열이 실제로 export"되고, Spring은 이를 "속성이 존재함(빈 값)"으로 해석해 `@Value`의
  `:defaultValue` 폴백이 적용되지 않는다.** 결과적으로 데모 계정 로그인이 전부 401로 막히는데 원인을 알기
  어렵다. 그래서 `.env.example`은 선택적 값들을 기본적으로 주석 처리(`# KEY=`)해뒀다 - 정말 커스텀 값이
  필요할 때만 주석을 풀고 값을 채울 것. 이 프로젝트에서 새로운 선택적 환경변수를 추가할 때도 같은 패턴을 따를 것.
- **Gradle 데몬이 재사용되면 새로 바뀐 환경변수(.env 등)가 반영되지 않을 수 있다.** `./gradlew bootRun`을
  실행하기 전 셸의 환경변수를 바꿨는데 이전 데몬이 살아있으면, 데몬이 처음 기동될 때의 환경을 계속 들고
  있어서 조용히 예전 값으로 동작한다. 환경변수를 바꾼 뒤에는 `./gradlew --stop`으로 데몬을 먼저 종료하고
  재기동하는 것이 안전하다(직접 겪은 문제 - `.env` 값이 반영 안 돼서 한참 헤맸다).

### Phase 3 설계 메모

- **⚠️ Elasticsearch 네이티브 `retriever.rrf`는 Platinum/Enterprise 유료 라이선스 전용 기능이다.**
  우리 `docker-compose.yml`의 ES는 기본(Basic/무료) 라이선스라서 RRF 쿼리를 보내면
  `security_exception: current license is non-compliant for [Reciprocal Rank Fusion (RRF)]`로 거부된다
  (2026-09-03 직접 확인). 반면 **BM25 단독 쿼리와 kNN 단독 쿼리는 Basic 라이선스에서도 무료**다.
  그래서 이 프로젝트는 (사용자와 상의 후) 네이티브 RRF retriever를 쓰지 않고, BM25 쿼리와 kNN 쿼리를
  각각 따로 실행한 뒤 **RRF 융합 공식(`score(doc) = Σ 1/(rank_constant + rank)`, rank_constant=60)을
  `HybridSearchService.fuseWithRrf()`에서 Java로 직접 재구현**해 두 결과를 합친다. Elastic 라이선스를
  구매하기로 결정이 바뀌면 `HybridSearchService`를 네이티브 `retriever.rrf` 단일 요청으로 되돌릴 수 있다
  (그때도 `rrf.filter`가 산하 서브 리트리버 전체에 자동 전파된다는 것까지는 확인해뒀다).
- RBAC 필터(`metadata.allowed_roles` `terms` 쿼리)는 BM25/kNN **두 쿼리 모두에 각각** 걸어야 한다.
  한쪽에만 걸면 그 쿼리의 결과만으로 권한 없는 문서가 새어나갈 수 있다.
- 두 쿼리 모두 raw JSON으로 조립해 `SearchRequest.of(b -> b.index(...).withJson(reader))`로 실행한다
  (Phase 2의 인덱스 생성과 동일한 패턴). 문자열 포매팅이 아니라 `Map`을 구성해 Jackson `ObjectMapper`로
  직렬화하는 방식을 쓴다 - 사용자 질의 텍스트에 따옴표/역슬래시가 섞여도 안전하다.
- **ADMIN은 RBAC 필터를 적용하지 않는다**(모든 문서 열람 가능). 그 외 역할은 `metadata.allowed_roles`에
  자신의 역할이 하나라도 포함된 문서만 조회된다. 이 필터는 클라이언트가 요청 바디로 지정하는 게 아니라
  **인증된 `Authentication`의 역할에서 서버가 직접 뽑아** 쓴다(`SearchController`) - 그렇지 않으면 누구나
  `allowedRoles=ADMIN`을 자칭해 모든 문서를 열람할 수 있어 RBAC 자체가 무의미해진다.
- 이 때문에 Phase 2의 **임시 permitAll `SecurityConfig`를 실제 HTTP Basic 인증(인메모리 `admin`/`user`
  계정)으로 교체**했다. `/api/documents`는 `ROLE_ADMIN`만, 나머지는 인증된 사용자면 누구나 호출 가능하다.
  데모 계정 비밀번호는 `app.security.demo-admin-password` / `app.security.demo-user-password`
  (`.env`의 `APP_SECURITY_DEMO_ADMIN_PASSWORD` 등)로 재정의할 수 있고, 기본값은 `*-local-dev-only`다.
  **실제 서비스에서는 반드시 사내 IdP/LDAP 연동이나 JWT 인증으로 교체할 것.**
- **`taskType`(예: 질의 임베딩에 `RETRIEVAL_QUERY` 사용) 설정은 Spring AI 2.0.1에서 사실상 no-op다.**
  `GoogleGenAiTextEmbeddingModel.call()` 소스를 직접 확인한 결과 `dimensions`는 Gemini API 요청에
  정상적으로 전달되지만 `taskType`은 옵션에 병합만 되고 실제 `EmbedContentConfig` 빌드 시 누락되어 있다
  (`spring-projects/spring-ai` 이슈 #5966, 확인일 2026-09-03, TODO 주석 존재). 그래서 `HybridSearchService`는
  질의 임베딩 시 별도 `RETRIEVAL_QUERY` 옵션을 주지 않고 그냥 `embeddingModel.embed(queryText)`를 쓴다 -
  동작하지 않는 옵션을 설정해 코드를 복잡하게 만들 이유가 없다. **이 이슈가 spring-ai 패치로 해결되면**
  Ingestion(RETRIEVAL_DOCUMENT)과 검색(RETRIEVAL_QUERY)에 서로 다른 task-type을 명시적으로 넣어
  비대칭 임베딩 검색 품질을 개선할 것.

### Phase 3 사용법 (로컬)

```bash
# USER 계정으로 검색 - allowedRoles에 USER가 없는 문서는 결과에서 자동 제외됨
curl -X POST http://localhost:8080/api/search \
  -u user:user-local-dev-only \
  -H "Content-Type: application/json" \
  -d '{"query":"연차는 며칠인가요?","topK":5}'

# ADMIN 계정으로 검색 - 모든 문서 대상
curl -X POST http://localhost:8080/api/search \
  -u admin:admin-local-dev-only \
  -H "Content-Type: application/json" \
  -d '{"query":"연차는 며칠인가요?"}'
```

### Phase 4 사용법 (로컬)

```bash
curl -X POST http://localhost:8080/api/chat \
  -u user:user-local-dev-only \
  -H "Content-Type: application/json" \
  -d '{"question":"연차는 며칠인가요?"}'
# => {"answer":"...(생성된 답변 + 출처 문구)...","citations":[{"documentTitle":...,"fileName":...,"pageNumber":...}]}
```

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
- (Phase 3에서 교체됨) `SecurityConfig`는 처음엔 임시로 모든 요청을 허용했으나, Phase 3에서 실제 HTTP Basic
  인증으로 교체했다. 아래 Phase 3 설계 메모 참고.
- `server.error.include-stacktrace: never`를 설정해도 **로컬 `bootRun`에서는 스택트레이스가 계속 노출된다.**
  `spring-boot-devtools`(developmentOnly 의존성)가 개발 편의를 위해 이 값을 강제로 `always`로 덮어쓰기 때문이며,
  devtools가 빠지는 실제 운영 빌드(`bootJar`)에서는 설정한 대로 `never`가 적용된다. 버그 아님 - 재확인하느라 시간 쓰지 말 것.
- Gemini 임베딩은 현재 `task-type: RETRIEVAL_DOCUMENT`로 전역 고정되어 있다. (Phase 3에서 확인: 현재 Spring AI
  버전에서는 `taskType`이 API 호출에 반영되지 않는 no-op이라 사실상 영향 없음 - 아래 Phase 3 메모 참고.)

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
