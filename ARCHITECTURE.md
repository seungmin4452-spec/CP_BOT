# CP_BOT 아키텍처 문서

사내 규정 PDF/Word 문서를 하이브리드 검색(BM25 + 벡터)으로 찾아 Gemini가 출처를 붙여 답변하는
RAG 챗봇의 내부 구조를 설명한다. Phase별 설계 배경과 트레이드오프는 `CLAUDE.md`에 더 자세히 기록되어
있으며, 이 문서는 "지금 시스템이 어떻게 동작하는가"를 한 곳에서 조망하는 데 목적이 있다.

## 1. 전체 구성

```
┌───────────────────────────────────┐    ┌────────────────────────────────────┐
│ 브라우저 (Thymeleaf, 세션 로그인)    │    │ curl / API 클라이언트 (HTTP Basic)  │
└───────────────────────────────────┘    └────────────────────────────────────┘
                                       ▼
┌───────────────────────────────────────────────────────────┐
│ Spring Boot 4.1.1 (Java 25)                               │
│                                                           │
│ Spring Security - Form Login + HTTP Basic, 역할 기반 RBAC  │
│                                                           │
│ REST API                                                  │
│   POST /api/documents, /api/documents/batch               │
│   POST /api/search                                        │
│   POST /api/chat                                          │
└───────────────────────────────────────────────────────────┘
                              ▼
┌──────────────────────────┐   ┌─────────────────────┐   ┌──────────────────┐
│ DocumentIngestionService │   │ HybridSearchService │   │ RagChatService   │
│                          │   │                     │   │                  │
│ PDF/Word/Zip             │   │ BM25 + kNN          │   │ 검색 결과         │
│ -> 조항 단위 청킹          │   │ -> RRF 융합 (Java)   │   │ -> 프롬프트 조립  │
└──────────────────────────┘   └─────────────────────┘   └──────────────────┘
                                      ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│ Spring AI 2.0.1 (BOM) - EmbeddingModel / ChatClient                          │
│                                                                              │
│ google-genai 스타터 (chat 전용 / embedding 전용, 별도 프로퍼티 네임스페이스)       │
└──────────────────────────────────────────────────────────────────────────────┘
                                       ▼
┌──────────────────────┐    ┌─────────────────────────────────────────┐
│ Google Gemini API    │    │ Elasticsearch 9.5.0 (Docker)            │
│                      │    │                                         │
│ gemini-3.5-flash     │    │ nori 분석기 커스텀 이미지                  │
│ gemini-embedding-001 │    │ content(BM25) + embedding(dense_vector) │
│                      │    │ + metadata.allowed_roles                │
└──────────────────────┘    └─────────────────────────────────────────┘
```

애플리케이션은 하나의 Spring Boot 모놀리스다. 화면(Thymeleaf)과 API가 같은 서버에서 나가고,
검색/색인 모두 Elasticsearch 한 곳에 저장된다. 별도의 벡터 DB나 프론트엔드 배포 파이프라인이 없다.

## 2. 기술 스택

| 영역 | 선택 | 비고 |
|---|---|---|
| Language | Java 25 (LTS) | Gradle toolchain 고정 |
| Framework | Spring Boot 4.1.1 | Spring Framework 7, `spring-boot-starter-webmvc` |
| AI Orchestration | Spring AI 2.0.1 (BOM) | Chat/Embedding이 서로 다른 스타터·프로퍼티 네임스페이스 |
| Vector/Search DB | Elasticsearch 9.5.0 (Docker, Basic 라이선스) | `analysis-nori` 플러그인 포함 커스텀 이미지 |
| Chat Model | `gemini-3.5-flash` (temperature 0.2) | |
| Embedding Model | `gemini-embedding-001`, 768차원 (MRL) | `text-embedding-004`는 폐기됨 |
| Security | Spring Security (Form Login + HTTP Basic, RBAC) | 인가는 항상 ES 쿼리 pre-filter |
| View | Thymeleaf + 순수 JS (SPA 아님) | 별도 프론트엔드 빌드 없음 |
| Word 파싱 | Apache Tika (`spring-ai-tika-document-reader`) | |
| PDF 파싱 | `PagePdfDocumentReader` (PDFBox 기반) | 페이지 단위 유지 |
| Markdown 렌더링 | `marked` + `DOMPurify` (로컬 벤더링) | LLM 출력은 반드시 sanitize 후 innerHTML |

## 3. 도메인 모듈 구조

```
com.sunjin.CP_BOT
├── ingestion/   DocumentIngestionController, DocumentIngestionService   (문서 적재)
├── search/      SearchController, HybridSearchService                  (하이브리드 검색)
├── chat/        ChatController, RagChatService                         (RAG 답변 생성)
├── web/         PageController                                         (Thymeleaf 라우팅)
├── config/      SecurityConfig, ElasticsearchIndexInitializer           (보안/인덱스 초기화)
└── common/
    ├── security/   AuthenticatedRoles     (Authentication → 역할 Set 변환)
    └── exception/  GlobalExceptionHandler (예외 → HTTP 상태 매핑)
```

레이어는 얇다: Controller가 요청을 받아 인증 정보에서 역할을 뽑고, Service가 Elasticsearch/Gemini와
직접 통신한다. Spring AI의 `VectorStore`/`RetrievalAugmentationAdvisor` 추상화는 의도적으로 쓰지
않고(§5), `ElasticsearchClient`를 저수준으로 직접 다룬다.

## 4. Ingestion 파이프라인 (문서 → 벡터 인덱스)

`DocumentIngestionService`가 담당하며 흐름은 다음과 같다.

```
업로드(PDF/DOCX/DOC, 단건 또는 zip)
   │
   ▼
① Reader로 원문 추출
   - PDF  → PagePdfDocumentReader (페이지 1개 = Document 1개, 페이지 번호 메타데이터 보존)
   - Word → TikaDocumentReader   (문서 전체를 Document 1개로 읽음, 페이지 개념 없음)
   │
   ▼
② "제N조" 조항 경계 청킹 (splitIntoChunks)
   - 여러 페이지 텍스트를 순서대로 이어붙여 하나의 fullText로 합침
   - 정규식 (?m)^제\s*\d+조(?:의\s*\d+)? 로 "줄 시작"에 오는 조항 번호만 경계로 인식
     (본문 중간의 "제3조에 따라" 같은 참조는 경계로 오인하지 않음)
   - 조항 경계를 하나도 못 찾으면 문서 전체를 조항 하나로 취급(안전한 폴백)
   - 조항 하나가 500토큰을 넘을 때만 TokenTextSplitter로 추가 분할
   - PDF는 조항이 걸친 페이지 범위를 page_number~end_page_number로 재계산
   │
   ▼
③ Gemini 임베딩 (embedInBatches)
   - EmbeddingModel.embed(List<String>) 호출, 최대 100개씩 배치 분할
     (Gemini API 제약: 배치당 최대 100개 텍스트)
   │
   ▼
④ Elasticsearch bulk 색인 (bulkIndex → toElasticsearchDocument)
   - content(원문), embedding(float[]), metadata{file_name, document_title,
     page_number, end_page_number, category, allowed_roles, ingested_at}
   - allowed_roles(List<String>)는 Spring AI Document.metadata가 배열을 지원하지
     않아 최종 ES 문서 조립 시점에만 추가됨
```

**zip 일괄 업로드**(`ingestZip`)는 `ZipInputStream`으로 직접 순회하며(추가 라이브러리 없음):
- 문서명 = zip 항목 파일명에서 확장자만 제거
- 카테고리 = zip 안 최상위 폴더명(루트면 "일반")
- 개별 파일이 손상/미지원 형식이어도 zip 전체를 실패시키지 않고 `skipped` 목록에 사유와 함께 기록
- 열람 권한(allowedRoles)만 zip 전체에 공통 적용

메모리 특성: zip 원본 자체는 Tomcat이 디스크에 스풀하고, 항목은 하나씩 순차 처리하며 압축 해제된
내용만 `ByteArrayOutputStream`으로 힙에 올린다 — 즉 메모리 사용량은 zip 전체 크기가 아니라
**zip 안에서 가장 큰 개별 파일 하나의 크기**에 비례한다.

## 5. 인덱스 스키마 (Elasticsearch)

애플리케이션 기동 시 `ElasticsearchIndexInitializer`(`ApplicationRunner`)가
`elasticsearch/company-policy-index-mapping.json`으로 인덱스를 명시적으로 생성한다
(`initialize-schema: false`로 Spring AI 자동 생성은 꺼둠).

```json
{
  "content":   { "type": "text", "analyzer": "korean_nori_analyzer" },
  "embedding": { "type": "dense_vector", "dims": 768, "similarity": "cosine" },
  "metadata": {
    "file_name": "keyword", "document_title": "keyword",
    "page_number": "integer", "end_page_number": "integer",
    "category": "keyword", "allowed_roles": "keyword",
    "ingested_at": "date"
  }
}
```

- `korean_nori_analyzer`: `nori_tokenizer`(decompound_mode=mixed) + `nori_part_of_speech` +
  `lowercase` — 한국어 형태소 분석 기반 BM25 품질 확보. `docker/elasticsearch/Dockerfile`이
  `analysis-nori` 플러그인을 설치한 커스텀 ES 이미지를 빌드한다.
- `dense_vector.dims`(768)는 `application.yml`의 `spring.ai.google.genai.embedding.text.dimensions`,
  `spring.ai.vectorstore.elasticsearch.dimensions`와 반드시 일치해야 한다(Gemini 임베딩 모델은 MRL을
  지원해 768로 잘라 쓴다).
- **매핑 JSON에 `index_options`를 명시하지 않았기 때문에 실제 kNN 인덱스 타입은 ES가 자동으로 고른다.**
  2026-09-07 로컬 클러스터(`GET /company-policy-index/_mapping`, `GET /_license`)로 실측 확인한 결과:
  ```json
  "embedding": {
    "type": "dense_vector", "dims": 768, "index": true, "similarity": "cosine",
    "index_options": {
      "type": "bbq_hnsw", "m": 16, "ef_construction": 100,
      "rescore_vector": { "oversample": 3.0 }
    }
  }
  ```
  라이선스는 `basic`. ES는 `index_options` 미지정 시 1순위로 `bbq_disk`(BBQ 압축 벡터를 디스크 기반으로
  다루는 최신 기본값)를 시도하는데, 이 타입은 Enterprise 유료 라이선스 전용 기능이라 Basic에서는
  자동 폴백되어 **768차원(≥384) 기준 다음 기본값인 `bbq_hnsw`**가 적용된다(HNSW 그래프 탐색 + BBQ로
  압축된 벡터 저장). `rescore_vector.oversample: 3.0`은 압축으로 인한 정확도 손실을 보정하기 위해
  압축 벡터로 topK의 3배를 후보로 뽑은 뒤 원본 벡터로 재채점(rescore)하는 옵션으로, 이것도 ES가
  자동으로 붙여준 기본값이다(명시적으로 설정한 적 없음). ES 버전이 올라가거나 라이선스가 바뀌면
  이 자동 선택 결과도 바뀔 수 있으므로, 정확한 값이 필요하면 매번 `_mapping`으로 재확인할 것
  (§11의 "버전 churn 대응" 원칙과 동일).
- **Spring AI의 `ElasticsearchVectorStoreAutoConfiguration`은 명시적으로 비활성화**되어 있다. 이유는
  두 가지: (1) `Document.metadata`가 배열을 지원하지 않아 RBAC용 `allowed_roles`(List)를 담을 수 없고,
  (2) 자동 인덱스 생성 타이밍이 `ApplicationRunner`(컨텍스트 기동 이후)와 충돌한다. 그래서 색인/검색
  모두 `ElasticsearchClient`를 직접 쓴다.

## 6. 하이브리드 검색 알고리즘 (BM25 + kNN + 애플리케이션 레벨 RRF)

`HybridSearchService.search()`의 핵심 흐름:

```
1. 질의 텍스트 임베딩 (embeddingModel.embed(queryText))
2. BM25 쿼리 실행 — content 필드에 nori 분석기로 match 쿼리
3. kNN 쿼리 실행   — embedding 필드에 대해 cosine 유사도 검색
4. 두 결과를 RRF(Reciprocal Rank Fusion) 공식으로 애플리케이션에서 직접 융합
5. 상위 topK 반환
```

**왜 네이티브 `retriever.rrf`를 쓰지 않는가**: Elasticsearch의 RRF retriever는 Platinum/Enterprise
유료 라이선스 전용 기능이라 이 프로젝트가 쓰는 Basic(무료) 라이선스에서는
`security_exception: current license is non-compliant for [Reciprocal Rank Fusion (RRF)]`로
거부된다. 반면 BM25 단독 쿼리와 kNN 단독 쿼리는 Basic 라이선스에서도 무료다. 그래서 RRF의 융합
수식만 Java로 재구현했다:

```
score(doc) = Σ 1 / (rank_constant + rank)     (rank_constant = 60, rank는 1부터 시작)
```

두 쿼리(BM25, kNN) 각각에서 얻은 순위를 위 공식으로 합산해 정렬한다 — 결과에 등장한 리스트가
많을수록(양쪽 다 상위에 있을수록) 최종 점수가 높아지는 구조. 후보군은 `topK * 5`(최소 50)로
넉넉히 뽑은 뒤 융합 후 상위 topK만 반환한다.

**RBAC pre-filter**: 인증된 사용자가 `ADMIN`이 아니면, BM25/kNN **두 쿼리 모두에 각각**
`terms` 필터(`metadata.allowed_roles`)를 건다. 한쪽에만 걸면 그 쿼리 결과만으로 권한 없는 문서가
새어나갈 수 있기 때문이다. 필터에 쓰이는 역할 집합은 요청 바디가 아니라 서버가
`SecurityContext`(`Authentication`)에서 직접 추출한다(`AuthenticatedRoles.extract`) — 클라이언트가
스스로 역할을 자칭해 모든 문서를 열람하는 것을 원천 차단한다. ADMIN은 필터 없이 전체 문서를 검색한다.

쿼리는 문자열 포매팅이 아니라 `Map<String,Object>`을 Jackson으로 직렬화해 raw JSON으로 조립
(`SearchRequest.withJson(...)`) — 질의 텍스트에 따옴표/역슬래시가 섞여도 안전하다.

## 7. RAG 답변 생성

`RagChatService.ask()`:

```
1. HybridSearchService.search(question, callerRoles, topK=5) 호출
2. 검색 결과 0건 → Gemini Chat 호출 없이 고정 문구 즉시 반환
   ("제공된 사내 규정 문서에서 관련 내용을 찾을 수 없습니다.")
   (질의 임베딩은 검색 자체에 필요해 항상 수행됨 — 아낄 수 있는 건 Chat Completion 호출뿐)
3. 검색 결과를 "[자료 N] 문서: .. / 파일: .. / 페이지: .." 형식으로 프롬프트에 조립
4. ChatClient.prompt().system(...).user(...).call() 으로 Gemini 호출
5. 답변 텍스트 + citations(문서명/파일명/페이지, distinct) 를 함께 반환
```

- **Spring AI의 `QuestionAnswerAdvisor`/`RetrievalAugmentationAdvisor`를 쓰지 않는다** — 두 Advisor
  모두 내부적으로 `VectorStore`를 전제로 하는데, §5에서 설명한 이유로 `VectorStore` 자동설정을
  껐기 때문이다. 대신 `ChatClient.Builder`만 주입받아 `RagChatService`가 검색 결과로 프롬프트를
  직접 조립한다.
- **환각 억제**: 시스템 프롬프트가 "참고 자료에 없으면 추측하지 말고 고정 문구로 답하라"를 강제.
- **RBAC은 검색 단계에서 이미 차단**되므로, LLM은 애초에 권한 없는 문서 내용을 프롬프트 컨텍스트에서
  보지 못한다(프롬프트로 "이건 보지 마"라고 지시하는 방식이 아님).
- **출처는 답변 텍스트가 아니라 구조화된 `citations` 배열이 유일한 출처(source of truth)**다. 시스템
  프롬프트가 "답변 텍스트에는 출처를 언급하지 말라"고 명시하고, 화면(§9)이 답변 박스 아래에 별도로
  citations를 렌더링한다. 페이지 번호가 없는 자료(Word 문서)는 컨텍스트에 "없음(페이지 구분 없는 문서)"로
  표기되고 화면에서도 ", p.N" 부분이 통째로 생략된다(null-safe).

## 8. 인증/인가 (Spring Security)

`SecurityConfig`가 두 인증 경로를 동시에 지원한다.

| 경로 | 대상 | 메커니즘 |
|---|---|---|
| 브라우저 | Thymeleaf 화면 | 세션 기반 폼 로그인(`formLogin`), 로그인 성공 시 세션 쿠키 발급 |
| API 클라이언트 | curl 등 | HTTP Basic (`httpBasic`), 인메모리 사용자(`admin`/`user`) |

- 인메모리 계정: `admin`(ROLE_ADMIN) / `user`(ROLE_USER), 비밀번호는
  `app.security.demo-admin-password` / `app.security.demo-user-password`(`.env`)로 오버라이드 가능.
  **실제 운영 전환 시 사내 SSO(OIDC/SAML)로 반드시 교체**할 것을 전제로, 인증 진입점만 교체하면
  RBAC 로직(`AuthenticatedRoles`)은 그대로 재사용 가능한 구조로 설계됨.
- `httpBasic()`과 `formLogin()`을 동시에 켜면 인증 실패 시 기본 진입점이 하나로 고정되는 문제(항상
  Basic 팝업이 이김)가 있어, `exceptionHandling().defaultAuthenticationEntryPointFor(...)`로
  **브라우저 요청(Accept: text/html)만 `/login`으로 리다이렉트**하고 나머지는 Basic 401 challenge를
  따르도록 분리. `MediaTypeRequestMatcher`의 `ignoredMediaTypes`에 `MediaType.ALL`을 명시해 curl의
  기본 `Accept: */*`가 text/html로 오인 매칭되는 것을 방지.
- **CSRF**: `Authorization` 헤더가 있는 요청(curl -u 등)만 예외 처리. 세션 쿠키 기반 화면 fetch는
  CSRF 보호 대상 — `<meta name="_csrf">` 태그(세션 리포지토리 + meta 태그 패턴)를 `chat.js`가 읽어
  헤더에 실어 보낸다.
- **인가 규칙**: `/admin/**`(화면+API)와 `POST /api/documents`, `/api/documents/batch`는 `ROLE_ADMIN`
  전용, 그 외 `/api/**`는 인증된 사용자면 누구나 호출 가능. 인가는 Spring Security
  `authorizeHttpRequests`(URL 레벨)와 검색/채팅 서비스 내부의 RBAC pre-filter(§6, 문서 레벨) 두 층으로
  이루어진다.

## 9. 웹 프론트엔드 (Phase 5)

별도 SPA 없이 **Thymeleaf 서버 렌더링 + 순수 JS**:

- `templates/chat.html` + `static/js/chat.js`: 채팅 화면. Gemini 응답을 `marked`(Markdown→HTML) +
  `DOMPurify`(살균)로 렌더링 — **LLM 출력을 `innerHTML`에 꽂기 전 항상 sanitize**해 검색 문서/질문에
  섞여든 프롬프트 인젝션이 스크립트로 실행되는 것을 차단. citations는 답변 bubble과 함께
  `.assistant-content`(flex-column) 래퍼로 감싸 세로로 쌓이게 배치.
- `templates/admin-documents.html` + `static/js/admin-documents.js`: ADMIN 전용 문서 업로드 화면.
  단건 업로드와 zip 일괄 업로드(다중 zip 선택 시 프런트에서 순차 호출) 지원.
- `templates/login.html`: `th:action="@{/login}"`으로 Spring Security Thymeleaf 통합, hidden CSRF
  필드 자동 주입.
- 외부 CDN/폰트 의존 없이 `marked`/`DOMPurify`를 `static/js/vendor/`에 로컬로 벤더링(오프라인/방화벽
  환경 대응).

## 10. 데이터 흐름 요약 (End-to-End)

**Ingestion**: `PDF/Word/zip 업로드 → Reader → 조항 경계 청킹 → Gemini 임베딩(100개씩 배치) →
ES bulk 색인(content+embedding+metadata)`

**질의응답**: `사용자 질문 → 역할 추출(SecurityContext) → 질의 임베딩 → BM25/kNN 각각 RBAC 필터 적용해
ES 조회 → RRF 융합(Java) → 상위 5건으로 프롬프트 조립 → Gemini Chat → 답변 + citations 반환 →
화면에서 Markdown 렌더링(sanitize) + citations 배지 표시`

## 11. 핵심 설계 원칙 (요약)

1. **인가는 항상 쿼리 레벨 pre-filter로** — 검색 결과를 받은 뒤 애플리케이션에서 걸러내지 않는다.
2. **환각 억제** — 시스템 프롬프트가 컨텍스트에 없는 내용은 추측하지 않도록 강제.
3. **출처 표기 필수** — 문서명 + 페이지 번호를 구조화된 citations로 노출(답변 텍스트에는 넣지 않음).
4. **API Key는 하드코딩 금지** — 모든 설정은 `${GEMINI_API_KEY}` 등 환경변수 플레이스홀더만 사용.
5. **버전 churn 대응** — Gemini 모델명/Spring AI 프로퍼티/ES 하이브리드 검색 문법은 수개월 단위로
   바뀌므로, 새 코드를 넣기 전 공식 레퍼런스를 재확인한다(자세한 내용은 `CLAUDE.md` 참고).
