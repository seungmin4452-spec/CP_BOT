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
- [x] **Phase 5: 웹 프론트엔드** - Thymeleaf + 순수 JS 채팅 화면, 세션 기반 폼 로그인

### Phase 5 설계 메모

- 별도 SPA 프로젝트 대신 **Thymeleaf 서버 렌더링 + 순수 JS**를 선택했다. 이 앱은 같은 서버가 화면까지
  내려주는 모놀리식 구조라 별도 프론트엔드 저장소/빌드/배포 파이프라인이 필요 없고, 인증도 브라우저가
  자동 관리하는 세션 쿠키 하나로 충분하다.
- **인증은 JWT가 아니라 세션 기반(폼 로그인)을 선택했다.** 이 챗봇은 최종적으로 사내 SSO(OIDC/SAML)에
  붙을 예정인데, 사내 IdP 연동도 결국 브라우저 리다이렉트 후 세션 쿠키로 귀결되는 구조라 지금 세션 골격을
  잡아두면 나중에 인증 진입점만 `oauth2Login()`/SAML로 교체하면 된다(RBAC이 `Authentication`에서 역할을
  뽑는 `AuthenticatedRoles`는 그대로 재사용). JWT는 완전히 stateless한 별도 도메인 SPA나 여러 인스턴스에
  세션 공유 없이 로드밸런싱해야 하는 경우에 의미가 있는데 지금 구조엔 해당 없다.
- `SecurityConfig`는 **폼 로그인(브라우저)과 HTTP Basic(curl/API 클라이언트) 두 경로를 함께 유지**한다.
  Phase 2/3에서 이미 문서화한 curl 예제가 계속 동작해야 하기 때문. 다만 `httpBasic()`과 `formLogin()`을
  동시에 켜면 등록 순서와 무관하게 인증 실패 시의 기본 진입점이 하나로 고정된다(실측: 항상 Basic 팝업이
  이김 - 브라우저로 미인증 상태에서 `/`에 접속해도 예쁜 로그인 페이지 대신 브라우저 네이티브 Basic Auth
  다이얼로그가 떴다). 그래서 `exceptionHandling().defaultAuthenticationEntryPointFor(...)`로 **브라우저
  요청(Accept: text/html)만 `/login`으로 리다이렉트**하고 나머지는 httpBasic의 기본 진입점(401 challenge)을
  따르게 명시적으로 분리했다. 이때 `MediaTypeRequestMatcher`가 curl의 기본 `Accept: */*`도 text/html과
  호환된다고 판단해 매칭시켜버리는 것도 실측으로 확인했다 - `setIgnoredMediaTypes(Set.of(MediaType.ALL))`로
  와일드카드를 명시적으로 제외해야 진짜 브라우저만 골라낼 수 있다.
- **CSRF는 `Authorization` 헤더가 있는 요청(curl -u 등)만 예외 처리**한다. CSRF는 브라우저가 쿠키를
  요청에 자동으로 실어 보내는 상황(세션 기반 폼 로그인/화면 fetch)에서만 의미가 있고, Authorization
  헤더는 브라우저가 다른 사이트로의 요청에 자동으로 재전송하지 않으므로 curl/API 클라이언트 호출은
  CSRF 보호 대상이 아니다. 세션 쿠키로 인증되는 화면 쪽 `/api/chat` 호출은 CSRF 보호가 그대로 걸린다.
- Thymeleaf 페이지가 세션 기반 CSRF 토큰을 `<meta name="_csrf">`/`<meta name="_csrf_header">` 태그로
  내려주면(`chat.html`), `chat.js`가 이를 읽어 `fetch('/api/chat')` 호출 시 헤더에 실어 보낸다
  (Spring Security 공식 문서가 권장하는 "세션 리포지토리 + meta 태그" 패턴 - 쿠키 기반 CSRF 리포지토리는
  Thymeleaf처럼 서버가 매 요청마다 페이지를 새로 렌더링하는 구조에서는 불필요하게 복잡하다).
- 로그인 폼(`login.html`)은 `th:action="@{/login}"`으로 Thymeleaf의 Spring 통합을 타서 hidden CSRF
  필드가 자동으로 주입된다.
- 디자인은 오픈소스 디자인 시스템 Astryx(`astryx.atmeta.com`, React+StyleX 기반)의 다크/미니멀 톤
  (카드형 레이아웃, pill 버튼, 뉴트럴 컬러)을 참고했지만, 스택이 달라(Thymeleaf/순수 CSS) 컴포넌트를
  그대로 가져올 수 없어 색감·레이아웃 감각만 참고해 `static/css/style.css`를 새로 작성했다.
- 2026-09-04 로컬 브라우저(Chrome)로 end-to-end 검증: USER 로그인 → 채팅 질문 전송(CSRF 통과) →
  등록된 문서가 없어 고정 문구 응답 확인, ADMIN 로그인 → 헤더에 ADMIN 뱃지 노출, 로그아웃 →
  `/login?logout` 안내 문구 확인. curl Basic Auth 경로(`-u`)도 병행 검증해 기존 Phase 2/3/4 문서의
  curl 예제가 여전히 동작함을 확인했다.
- **문서 업로드 화면(`/admin/documents`)을 추가하고, Ingestion 파이프라인이 PDF뿐 아니라 Word(doc/docx)도
  읽을 수 있도록 확장했다.** `SecurityConfig`에 `/admin/**`를 ADMIN 전용으로 추가했고(화면 자체도 접근
  차단, 업로드 API의 ADMIN 제한과 이중으로 걸림), 화면은 `PageController`의 `/admin/documents` +
  `templates/admin-documents.html` + `static/js/admin-documents.js`로 구성했다(기존 `/api/documents`
  API를 그대로 fetch로 호출 - 별도 API를 새로 만들지 않았다).
- Word 파싱은 `spring-ai-tika-document-reader`(Apache Tika 기반 `TikaDocumentReader`)를 썼다. PDF용
  `PagePdfDocumentReader`와 달리 **파일 전체를 한 덩어리(Document 1개)로만 읽고 페이지 개념이 없다**
  (Tika 자체가 페이지 분할을 제공하지 않음, Spring AI 공식 레퍼런스로 확인). 그래서
  `DocumentIngestionService`는 파일 확장자(pdf/docx/doc)로 리더를 분기하되, `file_name` 메타데이터는
  더 이상 리더가 남긴 값이 아니라 업로드된 `MultipartFile.getOriginalFilename()`을 직접 써서 두 리더 모두
  일관되게 채운다. `page_number`/`end_page_number`는 PDF 청크에만 존재하고 Word 청크는 자연스럽게
  null이 된다(ES 매핑도 `integer` 타입이라 null 허용, 별도 매핑 변경 불필요).
- **페이지 번호가 없는(Word) 자료의 출처 표기가 "p.null"처럼 깨지지 않도록 null-safe하게 처리했다**:
  `RagChatService`의 시스템 프롬프트에 "페이지 정보가 없는 자료는 페이지 없이 표기하라"는 지시를 추가하고
  프롬프트 컨텍스트도 페이지 없음을 "없음(페이지 구분 없는 문서)"로 명시했으며, `chat.js`의 인용 칩 렌더링도
  `pageNumber`가 null이면 ", p.N" 부분을 아예 생략한다. 2026-09-04 실제 최소 docx 파일을 업로드하고
  Gemini가 "출처: 재택근무 규정 (sample-remote-work-policy.docx)" 형식(페이지 없이)으로 정확히 답변하는
  것까지 end-to-end 확인했다.
- **zip 일괄 업로드(`POST /api/documents/batch`, 화면은 `/admin/documents`의 "zip 일괄 업로드" 섹션)를
  추가했다.** 기존 단일 업로드는 사람이 문서명을 직접 입력하는데 zip 안 여러 파일에는 그럴 수 없어서,
  (1) 문서명은 파일명에서 확장자만 제거해 자동 생성, (2) 카테고리는 zip 안 항목 경로의 최상위 폴더명을
  자동 사용(폴더 없이 zip 루트에 있으면 "일반")하도록 설계했다 - 사용자와 상의해 이 방식으로 결정함.
  열람 권한(allowedRoles)만 zip 전체에 공통으로 적용된다(파일마다 다르게 줄 수 없음).
- zip 엔트리는 `java.util.zip.ZipInputStream`으로 직접 순회한다(새 라이브러리 의존성 추가 없이 JDK
  표준 API로 충분). 인코딩은 UTF-8로 지정했는데, 엔트리에 UTF-8 플래그가 있으면 `ZipInputStream`이
  그 플래그를 우선 사용하고 없을 때만 지정한 UTF-8로 해석한다 - 7-Zip(UTF-8 옵션)/macOS/Windows
  "PowerShell Compress-Archive"/구글 드라이브 등 최신 압축 도구는 대부분 이 플래그를 남기므로 한글
  파일명·폴더명도 깨지지 않는다(2026-09-04 PowerShell `Compress-Archive`로 만든 한글 폴더/파일명 zip으로
  직접 검증 완료 - `인사/휴가규정.docx` → `category: "인사"`, `document_title: "휴가규정"` 정확히 반영됨).
  다만 UTF-8 플래그 없이 옛날 Windows 방식(시스템 기본 코드페이지)으로 만든 zip은 한글이 깨질 수 있는데,
  이건 별도로 처리하지 않았다(발생 확률 낮고 사용자가 최근 도구로 압축한다면 문제 없음).
- **개별 파일이 깨져 있거나 지원하지 않는 형식이어도 zip 전체를 실패시키지 않고 해당 항목만 건너뛴 뒤
  사유와 함께 결과에 보고한다**(`BatchIngestionResult.skipped`) - zip 안에는 무관한 파일이 섞여 있는
  경우가 흔하기 때문(예: macOS로 압축한 zip의 `__MACOSX/` 부산물, 관련 없는 텍스트 파일 등). 성공 건이
  하나도 없으면 그때는 예외로 처리해 400을 응답한다. 2026-09-04 손상된 PDF(1개) + 지원하지 않는 .txt(1개)
  + 정상 docx 2개(폴더 있음/없음)를 섞은 zip으로 직접 검증: "2개 파일 색인 완료, 2개 건너뜀"과 각 항목의
  구체적인 건너뛴 사유가 화면에 정확히 표시됨을 확인했다.
- zip 크기 자체는 기존 `spring.servlet.multipart.max-file-size/max-request-size` 제한을 그대로 따른다.
  **원래 50MB였는데, 실제 업로드 대상 zip이 최소 200MB~최대 1.4GB라는 것을 확인하고 2GB로 올렸다**
  (무제한(-1) 대신 명시적 상한을 둬서 실수로 지나치게 큰 업로드가 서버를 무한정 잡아먹는 것은 막음).
  2026-09-04 120MB 랜덤 바이트 zip(고의로 파싱 실패하는 내용)을 업로드해 이전이면 걸렸을 413이 더 이상
  뜨지 않고 요청이 애플리케이션 로직까지 정상 도달하는 것을 curl로 직접 확인했다. zip bomb(압축 해제 시
  원본보다 비정상적으로 커지는 공격) 방어는 추가하지 않았다(ADMIN 전용 사내 도구라 위협 모델상 과한
  설계로 판단, CLAUDE.md의 "발생할 수 없는 상황에 대한 방어 코드 지양" 원칙에 따름).
- **메모리 사용량은 zip 전체 크기가 아니라 "zip 안에서 가장 큰 개별 파일 1개" 크기에 비례한다.**
  업로드된 zip 원본은 Tomcat이 임시 파일로 스풀하지만(`spring.servlet.multipart.file-size-threshold`
  기본값이 즉시 디스크로 쓰는 설정이라 zip 전체가 힙에 올라가지 않음), `DocumentIngestionService.ingestZip()`은
  항목을 하나씩 순서대로만 처리하면서 각 항목의 압축 해제된 내용을 `ByteArrayOutputStream`으로 힙에 담는다
  - 그래서 zip이 1.4GB여도 그 안의 개별 PDF/Word 파일들이 상식적인 크기(수십 MB 이내)라면 문제없다.
  다만 zip 안에 유난히 큰 단일 파일(예: 수백 MB짜리 PDF 한 개)이 섞여 있으면 그 파일 처리 시점에 순간적으로
  힙 사용량이 커질 수 있다 - 실제로 이런 초대형 개별 문서가 나오면 항목을 디스크에 임시 파일로 먼저 쓰고
  그 파일을 가리키는 `Resource`로 처리하도록 바꿔야 한다(지금은 안 그럼 - 필요해지면 그때 바꿀 것).
- **zip 파일도 여러 개를 한 번에 선택해 업로드할 수 있다** (`admin-documents.html`의 zip input에
  `multiple` 추가). 백엔드 API(`/api/documents/batch`)는 파일 1개만 받는 구조를 그대로 두고, 프런트엔드
  (`admin-documents.js`)가 선택된 zip들을 **순차적으로** 하나씩 호출해 결과(성공/건너뜀)를 합쳐서 보여준다
  - 새 API를 만들 필요가 없었다. zip이 2개 이상일 때는 건너뛴 항목 이름 앞에 `zip파일명 » ` 접두어를 붙여
    어느 zip에서 왔는지 구분한다. 한 zip이 완전히 실패해도(예: zip 자체가 손상) 나머지 zip은 계속
    처리하고 실패한 zip 이름만 별도로 모아 보여준다.
  - **주의(재발 방지용 기록)**: 이 기능을 추가하며 HTML/JS만 고치고 `bootRun`을 재시작하지 않았더니
    브라우저에 예전 화면(= `multiple` 속성 없는 input)이 계속 떴다. 정적 리소스(`templates`, `static`)는
    Gradle `bootRun`이 `processResources`로 `src/main/resources`를 `build/resources/main`에 복사한
    뒤 그걸 서빙하는데, **이미 떠 있는 프로세스는 재시작 전까지 예전 복사본을 계속 쓴다** - devtools가
    막아주는 건 Java 클래스 핫스왑이지, 이미 시작된 프로세스가 새로 바뀐 `src/main/resources` 내용을
    자동으로 다시 읽어오게 해주는 게 아니다. 그래서 템플릿/정적 리소스를 고친 뒤에는 반드시
    `bootRun`을 재시작해야 한다(2026-09-04 실제로 이 문제로 한 번 헛디딤 - `multiple` 없는 예전 폼이
    떠서 두 번째 zip이 조용히 무시됐었다).
- **실제 1.4GB짜리 규정 zip 5개를 업로드하면서 실전에서만 드러난 버그 두 개를 고쳤다:**
  1. `BatchEmbedContentsRequest.requests: at most 100 requests can be in one batch` - Gemini 임베딩 API가
     한 번의 배치 요청에 텍스트를 최대 100개까지만 받는다. 청크가 500토큰 기준이라 웬만한 문서는 100개를
     안 넘지만, 244페이지짜리 대형 문서 하나가 538개 청크로 쪼개지면서 실제로 터졌다. `DocumentIngestionService.embedInBatches()`가
     텍스트 목록을 100개씩 나눠 순차적으로 `embeddingModel.embed()`를 호출하도록 고쳤다(2026-09-04
     동일한 538청크 문서로 재검증 완료 - 정상적으로 6번 나눠 호출되며 색인 성공).
  2. zip 안 파일이 전부 실패하면(예: 확장자가 전부 지원 안 되는 파일들) 예전엔 개별 실패 사유를 다 버리고
     "색인 가능한 파일을 찾지 못했습니다"라는 뭉뚱그린 예외 하나만 던졌다. `ingestZip()`을 고쳐서 **성공
     건이 0개여도 실패로 처리하지 않고 개별 사유(`skipped`)를 그대로 응답에 담아 반환**하게 했다 - zip에
     파일 항목 자체가 하나도 없을 때(빈 zip/폴더만 있음)만 진짜 예외를 던진다. 이 덕분에 "CI 운영규정"
     zip처럼 `.psd`/`.ai` 디자인 파일만 들어있는 케이스가 실제로 그게 원인이었다는 걸 확인할 수 있었다.
- **청킹을 토큰 개수 기반에서 "제N조" 조항 경계 기반으로 바꿨다.** 사내 규정 문서가 전부 "제1조(목적)"
  형식으로 통일돼 있다는 걸 사용자에게 확인받고 도입. `DocumentIngestionService.splitIntoChunks()`가
  `(?m)^제\s*\d+조(?:의\s*\d+)?` 정규식(**줄 시작**에 오는 조항 번호만 인정 - "제3조에 따라"처럼 문장
  중간의 다른 조항 참조가 경계로 오인되지 않도록)으로 먼저 조항 단위 `Document`를 만들고, 그 다음
  `TokenTextSplitter`로 넘긴다. Spring AI 공식 문서 확인 결과 "chunkSize보다 작은 텍스트는 그대로 청크
  1개로 반환"되므로, 조항 하나가 500토큰을 안 넘으면 통째로 한 청크가 되고(목표했던 동작), 넘으면 그
  안에서만 추가로 쪼개진다. **PDF는 조항이 페이지 경계를 넘어갈 수 있어서** 여러 페이지 텍스트를 순서대로
  이어붙인 뒤 그 전체 텍스트에서 조항 경계를 찾고, 각 조항 청크의 `page_number`/`end_page_number`를 그
  조항이 실제로 걸쳐있는 페이지 범위로 재계산해서 넣는다(기존 필드를 그대로 재활용 - 원래는 페이지당
  청크 1개라 두 값이 항상 같았지만, 이제 조항이 페이지를 넘기면 진짜 범위가 될 수 있다). Word 문서는
  페이지 개념이 없으니 조항 경계만 찾는다. "제N조" 패턴을 하나도 못 찾는 문서(형식이 다른 파일)는 문서
  전체를 조항 하나로 취급해 예전과 동일하게 동작하는 안전한 폴백을 둬서, 이 형식을 따르지 않는 파일이
  섞여 있어도 깨지지 않는다. 2026-09-04 합성 테스트 문서로 검증: "제2조(적용범위)... **제1조에 따라**
  시행한다."처럼 문장 중간 조항 참조가 있는 케이스에서도 잘못된 경계 분할 없이 정확히 조항 단위로만
  나뉘는 것을 확인했다(4개 청크: 서문 1 + 조항 3). 이 로직 도입 전 구 방식(토큰 기반)으로 색인했던
  1,482개 청크는 전부 삭제하고 실제 규정 zip 전체를 새 로직으로 재적재했다(최종 3,414개 청크).
- **답변을 마크다운으로 렌더링하고, 출처 표기를 더 작고 컴팩트하게 바꿨다.** `marked`(마크다운→HTML)와
  `DOMPurify`(살균)를 `static/js/vendor/`에 로컬로 내려받아 벤더링했다(공개 CDN 런타임 의존 없이 오프라인/
  방화벽 환경에서도 동작하게, 이 앱의 기존 방침과 동일 - 외부 폰트도 안 씀). **LLM 응답을 `innerHTML`에
  꽂기 전에 반드시 `DOMPurify.sanitize()`를 거친다** - 검색된 문서 내용이나 질문에 프롬프트 인젝션으로
  스크립트/이벤트 핸들러가 섞여 들어와도 실행되지 않도록 막는 안전장치(살균 없이 LLM 출력을 innerHTML에
  바로 넣는 건 절대 금지). `marked.setOptions({ breaks: true })`도 켰다 - Gemini 응답이 단일 개행으로
  줄을 나누는 경우가 많은데, 마크다운 기본 규칙(빈 줄 2개 이상이어야 문단 구분)대로면 다 한 문단으로
  뭉쳐 보이기 때문. 출처 칩(`citation-chip`)은 폰트 크기를 줄이고 테두리를 없애 배지 느낌으로 가볍게
  만들었고, 표시 텍스트에서 파일명은 빼고 "문서명 p.페이지"만 보여주되(공간 절약), 원래 파일명은
  `title` 속성(마우스 오버 시 툴팁)으로 남겨뒀다. 다크 테마와 안 어울리던 브라우저 기본 스크롤바도
  `::-webkit-scrollbar`/`scrollbar-color`로 얇고 어두운 커스텀 스타일로 바꿨다(전역 적용 - `.chat-log`,
  `.page-body` 등 스크롤되는 모든 영역에 자동 적용됨).
- **답변 텍스트에서 "출처: ..." 문구를 완전히 없앴다.** 화면 하단 citations 영역과 답변 안 출처 문구가
  중복으로 보인다는 사용자 피드백에 따라, 시스템 프롬프트에서 출처 관련 지시를 전부 빼고 "답변 텍스트에는
  출처/문서명/파일명/페이지 번호를 언급하지 마십시오"로 명시했다(위 Phase 4 메모 갱신). 이제 출처는
  citations(화면 하단 배지)가 유일한 출처(source of truth)다.
- **출처 배지가 답변 박스 오른쪽에 붙어 보이던 레이아웃 버그를 고쳤다.** `chat.js`가 답변 bubble과
  citations를 `.msg`(assistant 메시지 컨테이너)의 직속 형제 요소로 붙였는데, `.msg`가 `display:flex`
  (기본 `flex-direction: row`)라 두 요소가 세로로 쌓이지 않고 가로로 나란히 배치됐다(citations가 답변
  박스 오른쪽 옆 여백에 떠 보임). `renderAssistantMessage()`가 이제 bubble과 citations를 `.assistant-content`
  래퍼(`display:flex; flex-direction:column`)로 한 번 더 감싸서 세로로 쌓이게 고쳤고, `max-width: 80%`도
  bubble이 아니라 이 래퍼로 옮겼다. 2026-09-04 브라우저로 재검증 완료.

### Phase 5 사용법 (로컬)

```bash
# 브라우저로 접속 (미인증 시 자동으로 /login 으로 이동)
open http://localhost:8080/

# 문서 업로드 화면 (ADMIN 전용, PDF/DOCX/DOC 업로드 가능)
open http://localhost:8080/admin/documents

# 데모 계정: admin/admin-local-dev-only, user/user-local-dev-only
# (커스텀 비밀번호는 .env의 APP_SECURITY_DEMO_ADMIN_PASSWORD 등으로 재정의)
```

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
- 시스템 프롬프트는 컨텍스트에 없으면 추측 금지 및 고정 문구로만 답변하도록 지시한다. **출처는 답변
  텍스트에 넣지 않는다** - 검색 메타데이터에서 뽑은 구조화된 `citations` 배열만이 출처의 단일 출처(source
  of truth)이고, 화면(Phase 5, `chat.js`)이 답변 박스 아래에 별도 영역으로 렌더링한다. (2026-09-04 변경:
  원래는 LLM이 답변 끝에 "출처: ..." 문구도 같이 적게 하고 구조화된 citations도 병행 표시하는 이중 안전장치
  였는데, 화면에 출처가 두 번 겹쳐 보여서 사용자 피드백에 따라 텍스트 쪽을 없앴다. LLM이 출처 형식을 틀릴
  걱정을 안 해도 되니 오히려 더 단순해짐.)

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
3. **출처 표기 필수.** 근거 문서명 + 페이지 번호를 반드시 노출한다(단, 답변 텍스트 안이 아니라 화면의
   구조화된 citations 영역에 - Phase 5 설계 메모 참고). 이 메타데이터는 Ingestion 단계에서 Elasticsearch
   문서에 함께 저장돼 있어야 하며, 답변 생성 코드는 이를 누락 없이 노출해야 한다.
4. **API Key는 절대 하드코딩/커밋 금지.** `application.yml`은 항상 `${GEMINI_API_KEY}` 같은 플레이스홀더만 사용한다.
   `.env`는 `.gitignore`에 포함되어 있다.

## 코딩 컨벤션

- 주석은 한국어로, **"왜"가 비자명한 경우에만** 작성한다 (무엇을 하는지는 코드/네이밍으로 표현). 함수 단위 장문 Javadoc은 지양.
- 요청 범위를 벗어난 리팩토링/추상화를 함께 밀어넣지 않는다. 각 Phase가 요구하는 만큼만 구현한다.
- 실무 수준 예외 처리를 하되, 발생할 수 없는 상황에 대한 방어 코드는 추가하지 않는다 (예: Ingestion 파이프라인 내부 호출에 대한 과도한 null 체크).
- Elasticsearch 인덱스 매핑은 항상 명시적 JSON/Java 코드로 보여주고, `dense_vector`의 `dims`가
  `application.yml`의 embedding dimensions(현재 768)와 실제로 일치하는지 매 Phase마다 재확인한다.
