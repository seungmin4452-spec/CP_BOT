# CP_BOT — 사내 규정 Q&A RAG 챗봇

Spring Boot + Elasticsearch + Gemini 기반 RAG(검색 증강 생성) 챗봇.
사내 규정 문서(PDF/Word)를 임베딩하여 Elasticsearch에 적재하고, 하이브리드 검색(BM25 + Vector)과
RBAC 권한 사전 필터링을 거쳐 Gemini가 출처를 명시한 답변을 생성한다.

## 기술 스택

| 영역 | 선택 |
|---|---|
| Language | Java 25 (LTS, Gradle toolchain 고정) |
| Framework | Spring Boot 4.1.1 (Spring Framework 7) |
| AI Orchestration | Spring AI 2.0.1 (BOM) |
| Vector/Search DB | Elasticsearch 9.5.0 (Docker, 한국어 `nori` 분석기 포함 커스텀 이미지) |
| Chat Model | Gemini (`spring-ai-starter-model-google-genai`) |
| Embedding Model | `gemini-embedding-001` |
| Security | Spring Security — 폼 로그인(브라우저) + HTTP Basic(API) 병행, RBAC은 ES 쿼리 레벨 pre-filter로 처리 |
| View | Thymeleaf + 순수 JS (별도 SPA 없음) |

> 버전/모델명은 임의로 올리거나 내리지 않는다. AI 생태계 버전 churn과 관련한 세부 사항, 설계 배경,
> 트러블슈팅 기록은 [`CLAUDE.md`](./CLAUDE.md)에 상세히 정리되어 있다.

## 핵심 설계 원칙

1. **인가는 항상 쿼리 레벨 pre-filter로 처리한다.** 검색 결과를 받은 뒤 걸러내지 않고, 문서를 볼 권한이
   없는 사용자에게는 애초에 Elasticsearch가 해당 문서를 반환하지 않는다(`terms` 필터를 BM25/kNN 쿼리에 결합).
2. **환각 억제.** 시스템 프롬프트는 항상 "컨텍스트에 없는 내용은 추측하지 말고 답할 수 없다고 하라"를 포함한다.
3. **출처 표기 필수.** 근거 문서명 + 페이지 번호는 답변 텍스트가 아니라 화면의 구조화된 citations 영역에
   노출한다.
4. **API Key는 절대 하드코딩/커밋하지 않는다.** 항상 환경변수(`${GEMINI_API_KEY}`)로 주입한다.

## 주요 기능

- **문서 Ingestion** (`POST /api/documents`, `/api/documents/batch`): PDF/Word(doc/docx) 업로드 →
  "제N조" 조항 경계 기반 청킹 → Gemini 임베딩 → Elasticsearch bulk 색인. zip 일괄 업로드 및 다중 zip
  선택 업로드를 지원하며, 개별 파일이 깨져 있거나 지원하지 않는 형식이어도 해당 항목만 건너뛰고 사유를 보고한다.
- **하이브리드 검색** (`POST /api/search`): BM25(키워드)와 kNN(벡터) 쿼리를 각각 실행한 뒤 RRF(Reciprocal
  Rank Fusion) 공식으로 애플리케이션 레벨에서 직접 융합한다(ES Basic 라이선스에서는 네이티브
  `retriever.rrf`가 유료 기능이라 사용 불가). 인증된 사용자의 역할에 따라 RBAC 필터가 자동 적용된다.
- **RAG 채팅** (`POST /api/chat`): 검색 결과 기반으로 프롬프트를 조립해 Gemini에 전달하고, 답변과 함께
  구조화된 출처(citations) 목록을 반환한다. 검색 결과가 0건이면 Gemini 호출 없이 고정 문구를 즉시 반환한다.
- **웹 프론트엔드**: 세션 기반 폼 로그인, 채팅 화면(`/`, 마크다운 렌더링 + XSS 방지를 위한 살균 처리),
  문서 업로드 화면(`/admin/documents`, ADMIN 전용).

## 로컬 실행

### 1. Elasticsearch(+Kibana) 기동

```bash
docker compose up -d
```

- ES 상태 확인: `curl http://localhost:9200/_cluster/health`
- Kibana: http://localhost:5601

### 2. 환경변수 설정

```bash
cp .env.example .env
# .env를 열어 GEMINI_API_KEY를 채운다 (https://aistudio.google.com/apikey 에서 발급)
```

`.env`의 선택적 값(데모 계정 비밀번호 등)은 값이 빈 문자열이면 오히려 오작동하므로, 커스터마이즈가
필요할 때만 주석을 풀고 채운다. 자세한 내용은 `.env.example` 주석 참고.

### 3. 애플리케이션 실행

```bash
./gradlew bootRun
```

환경변수를 바꾼 뒤에는 `./gradlew --stop`으로 Gradle 데몬을 먼저 종료하고 재기동해야 반영된다.

### 4. 접속

```bash
open http://localhost:8080/            # 채팅 화면 (미인증 시 /login 으로 자동 이동)
open http://localhost:8080/admin/documents   # 문서 업로드 화면 (ADMIN 전용)
```

데모 계정: `admin` / `admin-local-dev-only`, `user` / `user-local-dev-only`
(실제 서비스 배포 전 반드시 사내 IdP/SSO 연동으로 교체할 것)

## API 사용 예시 (curl, HTTP Basic)

### 문서 업로드

```bash
curl -X POST http://localhost:8080/api/documents \
  -u admin:admin-local-dev-only \
  -F "file=@/path/to/규정.pdf" \
  -F "documentTitle=휴가 규정" \
  -F "category=인사" \
  -F "allowedRoles=USER" -F "allowedRoles=ADMIN"
```

### zip 일괄 업로드

```bash
curl -X POST http://localhost:8080/api/documents/batch \
  -u admin:admin-local-dev-only \
  -F "file=@/path/to/규정모음.zip" \
  -F "allowedRoles=USER" -F "allowedRoles=ADMIN"
```

### 검색

```bash
curl -X POST http://localhost:8080/api/search \
  -u user:user-local-dev-only \
  -H "Content-Type: application/json" \
  -d '{"query":"연차는 며칠인가요?","topK":5}'
```

### 채팅

```bash
curl -X POST http://localhost:8080/api/chat \
  -u user:user-local-dev-only \
  -H "Content-Type: application/json" \
  -d '{"question":"연차는 며칠인가요?"}'
# => {"answer":"...","citations":[{"documentTitle":...,"fileName":...,"pageNumber":...}]}
```

## 프로젝트 구조

```
src/main/java/com/sunjin/CP_BOT/
├── chat/            # RAG 채팅 API (ChatController, RagChatService)
├── ingestion/        # 문서 업로드/파싱/청킹/색인 (DocumentIngestionController, DocumentIngestionService)
├── search/          # 하이브리드 검색 + RBAC 필터 (SearchController, HybridSearchService)
├── web/              # Thymeleaf 화면 라우팅 (PageController)
├── config/           # SecurityConfig, ElasticsearchIndexInitializer
└── common/           # 예외 처리, 인증 역할 추출 유틸

src/main/resources/
├── application.yml
├── elasticsearch/company-policy-index-mapping.json   # nori 분석기 포함 커스텀 인덱스 매핑
├── templates/        # login.html, chat.html, admin-documents.html
└── static/           # style.css, chat.js, admin-documents.js, vendor(marked, DOMPurify)

docker/elasticsearch/  # analysis-nori 플러그인을 설치한 커스텀 ES 이미지 Dockerfile
```

## 개발 상태

- [x] Phase 1: 인프라/프로젝트 세팅
- [x] Phase 2: 문서 Ingestion 파이프라인
- [x] Phase 3: 하이브리드 검색 및 RBAC 필터링
- [x] Phase 4: RAG 채팅 API
- [x] Phase 5: 웹 프론트엔드

각 Phase의 설계 배경, 트레이드오프, 실전에서 발견한 버그와 수정 내역은 [`CLAUDE.md`](./CLAUDE.md)에
날짜별로 기록되어 있다.

## 참고 문서

- [`CLAUDE.md`](./CLAUDE.md) — 기술 스택 고정 사유, Phase별 설계 메모, 코딩 컨벤션, 트러블슈팅 기록
- [`ARCHITECTURE.md`](./ARCHITECTURE.md) — 아키텍처 문서
