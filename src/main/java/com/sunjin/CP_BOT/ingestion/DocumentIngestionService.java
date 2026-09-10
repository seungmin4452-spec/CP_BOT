package com.sunjin.CP_BOT.ingestion;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 사내 규정 문서(PDF, Word)를 읽어 청크 단위로 분할하고, Gemini 임베딩을 계산해 Elasticsearch에 적재한다.
 * <p>
 * PDF는 {@link PagePdfDocumentReader}로 페이지 단위를 유지해 읽고, Word(doc/docx)는 Apache Tika 기반
 * {@link TikaDocumentReader}로 문서 전체를 한 덩어리로 읽는다(Tika는 페이지 개념을 제공하지 않음) - 그래서
 * Word 문서에서 나온 청크는 {@code page_number} 메타데이터가 없다. 답변 생성(RagChatService)과 출처 UI는
 * 페이지 번호가 없는 자료를 "페이지 없음"으로 취급하도록 이미 null-safe하게 되어 있다.
 * <p>
 * zip 일괄 업로드({@link #ingestZip})는 문서명을 사람이 매번 입력할 수 없으므로 파일명(확장자 제외)에서,
 * 카테고리는 zip 안 최상위 폴더명에서 자동으로 뽑는다. 열람 권한(allowedRoles)만 zip 전체에 공통으로 적용된다.
 * <p>
 * Spring AI {@code Document}의 metadata는 String/int/float/boolean 값만 허용하기 때문에
 * (배열 불가) RBAC 필터링에 필요한 allowed_roles(List)는 Document metadata에 담지 않고,
 * 최종 Elasticsearch 색인 문서를 조립하는 단계에서 직접 추가한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestionService {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf", "docx", "doc");
    private static final String DEFAULT_CATEGORY = "일반";

    private final EmbeddingModel embeddingModel;
    private final ElasticsearchClient elasticsearchClient;

    @Value("${spring.ai.vectorstore.elasticsearch.index-name}")
    private String indexName;

    public IngestionResult ingest(MultipartFile file, String documentTitle, String category, Set<String> allowedRoles) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("업로드된 문서 파일이 비어 있습니다.");
        }
        if (allowedRoles == null || allowedRoles.isEmpty()) {
            throw new IllegalArgumentException("열람 권한(allowedRoles)이 지정되지 않은 문서는 적재할 수 없습니다.");
        }

        String fileName = file.getOriginalFilename();
        IngestedChunks result = ingestOne(toResource(file), fileName, documentTitle, category, allowedRoles);

        log.info("문서 적재 완료: title={}, file={}, units={}, chunks={}, allowedRoles={}",
                documentTitle, fileName, result.sourceUnitCount(), result.chunkCount(), allowedRoles);
        return new IngestionResult(documentTitle, result.sourceUnitCount(), result.chunkCount(), indexName);
    }

    /**
     * zip 안의 pdf/docx/doc 파일을 모두 찾아 적재한다. 개별 파일이 깨져 있거나 지원하지 않는 형식이어도
     * 전체를 실패시키지 않고 해당 항목만 건너뛴 뒤 결과에 사유와 함께 보고한다(zip 안에는 관계없는 파일이
     * 섞여 있는 경우가 흔하기 때문 - 예: macOS로 압축한 zip의 "__MACOSX/" 부산물).
     */
    public BatchIngestionResult ingestZip(MultipartFile zipFile, Set<String> allowedRoles) {
        if (zipFile.isEmpty()) {
            throw new IllegalArgumentException("업로드된 zip 파일이 비어 있습니다.");
        }
        if (allowedRoles == null || allowedRoles.isEmpty()) {
            throw new IllegalArgumentException("열람 권한(allowedRoles)이 지정되지 않은 문서는 적재할 수 없습니다.");
        }
        String zipFileName = zipFile.getOriginalFilename();
        if (zipFileName == null || !zipFileName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new IllegalArgumentException("zip 파일만 업로드할 수 있습니다: " + zipFileName);
        }

        List<BatchItemResult> succeeded = new ArrayList<>();
        List<SkippedEntry> skipped = new ArrayList<>();

        // 항목 이름의 UTF-8 플래그가 있으면 ZipInputStream이 이를 우선 사용하고, 없을 때만 아래 지정한
        // 인코딩(UTF-8)으로 해석한다 - 최신 압축 도구(7-Zip UTF-8 옵션, macOS, 구글 드라이브 등)는
        // 대부분 UTF-8 플래그를 남기므로 한글 파일명도 문제없이 읽힌다.
        try (ZipInputStream zis = new ZipInputStream(zipFile.getInputStream(), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName();
                String leafFileName = leafFileName(entryName);

                ByteArrayOutputStream entryBytes = new ByteArrayOutputStream();
                zis.transferTo(entryBytes);

                try {
                    String category = categoryFromEntryName(entryName);
                    String documentTitle = titleFromFileName(leafFileName);
                    Resource resource = toResource(entryBytes.toByteArray(), leafFileName);

                    IngestedChunks result = ingestOne(resource, leafFileName, documentTitle, category, allowedRoles);

                    succeeded.add(new BatchItemResult(entryName, documentTitle, category, result.chunkCount()));
                    log.info("zip 항목 적재 완료: entry={}, title={}, category={}, chunks={}",
                            entryName, documentTitle, category, result.chunkCount());
                } catch (IllegalArgumentException e) {
                    skipped.add(new SkippedEntry(entryName, e.getMessage()));
                    log.warn("zip 항목 건너뜀: entry={}, reason={}", entryName, e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("zip 파일을 읽는 중 오류가 발생했습니다.", e);
        }

        // succeeded가 비어도(전부 건너뜀) 예외로 던지지 않고 그대로 반환한다 - 예전엔 여기서 뭉뚱그린
        // 예외를 던져서, zip 안 파일들이 왜 전부 실패했는지(확장자 문제/파싱 실패 등) 알 수 없었다.
        // zip에 파일 항목 자체가 하나도 없을 때만(디렉토리만 있거나 완전히 빈 zip) 안내 예외를 던진다.
        if (succeeded.isEmpty() && skipped.isEmpty()) {
            throw new IllegalArgumentException("zip 안에서 파일 항목을 찾지 못했습니다(빈 zip이거나 폴더만 있음).");
        }

        log.info("zip 일괄 적재 완료: file={}, succeeded={}, skipped={}", zipFileName, succeeded.size(), skipped.size());
        return new BatchIngestionResult(succeeded, skipped);
    }

    // 캐릭터/CI 운영규정 폴더에 실제로 포스터·배너용 순수 이미지 PDF(예: 공고문 배너)가 다수 섞여 있는 것을
    // 확인했다 - 이런 파일은 PagePdfDocumentReader/TikaDocumentReader로 읽어도 추출되는 텍스트가 몇 글자
    // 수준(실측 8바이트)이라 색인해도 검색/답변에 쓸 내용이 없다. 반면 실제 규정 중 가장 짧은 축(윤리헌장 등
    // 선언문류)도 최소 1,500자 이상은 나와서, 이 둘을 가르는 데는 낮은 임계값으로도 충분하다. 스캔본(본문이
    // 이미지인 규정 PDF)은 이 검사로 걸러지는 게 아니라 별개 문제(OCR 필요)라 여기서 다루지 않는다.
    private static final int MIN_EXTRACTABLE_TEXT_LENGTH = 50;

    private IngestedChunks ingestOne(Resource resource, String fileName, String documentTitle, String category, Set<String> allowedRoles) {
        List<Document> sourceDocuments = readDocuments(resource, fileName);
        int extractedTextLength = sourceDocuments.stream()
                .mapToInt(document -> document.getText().strip().length())
                .sum();
        if (extractedTextLength < MIN_EXTRACTABLE_TEXT_LENGTH) {
            throw new IllegalArgumentException(
                    "추출 가능한 텍스트가 거의 없습니다(이미지 위주 파일로 추정): " + fileName);
        }
        List<Document> chunks = splitIntoChunks(sourceDocuments);
        bulkIndex(chunks, fileName, documentTitle, category, allowedRoles);
        return new IngestedChunks(sourceDocuments.size(), chunks.size());
    }

    private String leafFileName(String entryName) {
        int slashIndex = entryName.lastIndexOf('/');
        return slashIndex < 0 ? entryName : entryName.substring(slashIndex + 1);
    }

    private String categoryFromEntryName(String entryName) {
        int slashIndex = entryName.indexOf('/');
        return slashIndex < 0 ? DEFAULT_CATEGORY : entryName.substring(0, slashIndex);
    }

    private String titleFromFileName(String leafFileName) {
        int dotIndex = leafFileName.lastIndexOf('.');
        return dotIndex < 0 ? leafFileName : leafFileName.substring(0, dotIndex);
    }

    private List<Document> readDocuments(Resource resource, String originalFilename) {
        String extension = extractExtension(originalFilename);
        return switch (extension) {
            case "pdf" -> readPdfPages(resource);
            case "docx", "doc" -> readWordDocument(resource);
            default -> throw new IllegalArgumentException(
                    "지원하지 않는 파일 형식입니다 (pdf, docx, doc만 가능): " + originalFilename);
        };
    }

    private String extractExtension(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("파일 이름을 확인할 수 없습니다.");
        }
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filename.length() - 1) {
            throw new IllegalArgumentException(
                    "파일 확장자를 확인할 수 없습니다 (pdf, docx, doc만 가능): " + filename);
        }
        String extension = filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException(
                    "지원하지 않는 파일 형식입니다 (pdf, docx, doc만 가능): " + filename);
        }
        return extension;
    }

    private List<Document> readPdfPages(Resource pdfResource) {
        PdfDocumentReaderConfig readerConfig = PdfDocumentReaderConfig.builder()
                .withPageTopMargin(0)
                .withPageBottomMargin(0)
                // 페이지 단위를 유지해야 답변 생성 시 정확한 출처 페이지 번호를 표기할 수 있다.
                .withPagesPerDocument(1)
                .withPageExtractedTextFormatter(ExtractedTextFormatter.builder()
                        .withNumberOfTopTextLinesToDelete(0)
                        .build())
                .build();

        try {
            return new PagePdfDocumentReader(pdfResource, readerConfig).read();
        } catch (RuntimeException e) {
            // PDFBox는 손상되었거나 PDF가 아닌 파일에 대해 다양한 RuntimeException을 던진다.
            // 클라이언트 입력 문제이므로 400으로 응답할 수 있도록 IllegalArgumentException으로 변환한다.
            throw new IllegalArgumentException("PDF 파일을 파싱할 수 없습니다. 올바른 PDF 파일인지 확인하세요.", e);
        }
    }

    private List<Document> readWordDocument(Resource wordResource) {
        try {
            return new TikaDocumentReader(wordResource).read();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Word 파일을 파싱할 수 없습니다. 올바른 doc/docx 파일인지 확인하세요.", e);
        }
    }

    // "제1조", " 제 1 조", "제2조의2"처럼 줄 시작에 오는 조항 번호만 경계로 인정한다("제3조에 따라"처럼 문장
    // 중간에 등장하는 다른 조항 참조는 매치되지 않도록 줄 시작(^)을 요구함). 숫자와 "조" 사이의 공백(예: "제 1 조")과
    // 줄 맨 앞의 들여쓰기 공백(예: " 제 1 조[목 적]")도 허용하는데, 실제 규정 PDF 130개를 표본 조사한 결과 이
    // 띄어쓴 표기를 쓰는 문서가 전체의 약 20%(예: 전산장비관리규정, 여비규정, 정보보호정책, 사택 및 주거지원규정)
    // 였고, 이 문서들은 "제1조"만 인정하던 예전 정규식으로는 조항 경계를 하나도 못 찾아 문서 전체가 토큰 기준으로만
    // 쪼개지고 있었다. 다만 이렇게 공백을 허용하면 "제11조에 의한..."처럼 줄 첫머리에서 조항을 인용만 하는
    // 문장(개행이 우연히 그 위치에서 끊긴 경우)까지 걸릴 위험이 커지므로, 매치 직후에 한글이 바로 이어지면(즉
    // 조사가 공백 없이 붙으면) 제목이 아니라 본문 인용으로 보고 제외한다(실제 감사규정 "제18조(시정확인)\n제17조의
    // 시정요구에 대하여는..." 케이스로 확인). 이 필터를 거치고도 법령 인용표처럼 표 형태의 문서(예: 산업안전보건법
    // 위반 과태료표)에서 드물게 오탐이 남을 수 있지만, 그런 문서는 애초에 표 구조라 조항 단위 청킹의 이득이 없고
    // 회사 자체 규정도 아니어서(외부 참고자료) 감수할 수 있는 수준으로 판단했다.
    private static final Pattern ARTICLE_PATTERN =
            Pattern.compile("(?m)^\\s*제\\s*\\d+\\s*조(?:\\s*의\\s*\\d+)?(?![가-힣])");

    // 부칙(附則)은 "제N조" 형식을 쓰지 않고 "1. (시행일)", "①(시행일)"처럼 별도의 번호체계를 쓰는 경우가
    // 실제 규정 문서 표본의 절반 가까이에서 확인됐다. 이런 문서는 부칙 전체가 마지막 조항의 꼬리에 그대로
    // 붙어버려("제24조" 청크에 시행일 정보가 섞여 들어가는 식) 시행일/경과조치를 물어보는 질의의 근거가 흐려진다.
    // "부칙(채권관리매뉴얼 SCP-A2. 2011년 07월 08일 제정)"처럼 괄호가 바로 붙는 경우도 있어 줄 끝까지는 요구하지
    // 않는다. ARTICLE_PATTERN과 마찬가지로 직후에 한글이 바로 붙으면("부칙은 ...") 제목이 아닌 본문으로 보고 제외한다.
    private static final Pattern ADDENDUM_PATTERN =
            Pattern.compile("(?m)^\\s*※?\\s*부\\s*칙(?![가-힣])");

    // 목차(TOC) 페이지가 "제10조 (표제) ..................... 3"처럼 조항 패턴을 그대로 흉내내면서 점선 리더 뒤에
    // 페이지 번호를 붙이는 문서가 다수 확인됐다(정보보호정책류). 본문 조항 제목은 이런 형태로 끝나지 않으므로,
    // 매치된 줄의 나머지 부분이 점 3개 이상 + (선택적) 숫자로 끝나면 목차 항목으로 보고 경계에서 제외한다 -
    // 그렇지 않으면 목차 한 페이지가 조항마다 한 줄씩 잘려 의미 없는 초소형 청크가 수십 개 생긴다.
    private static final Pattern TOC_DOT_LEADER_SUFFIX = Pattern.compile("\\.{3,}\\s*\\d*\\s*$");

    // "Ⅰ. 담보평가"처럼 "제N조" 대신 로마숫자 대제목 + 소수점 하위목차("1.", "4.1", "4.3.1.1" 등)를 쓰는
    // 매뉴얼(예: 채권관리매뉴얼)이 표본에서 확인됐다. 소수점 항목("1.", "2.1" 등)은 다른 모든 문서에서도
    // 흔한 일반 목록 표기라 전역 경계로 쓰면 오탐이 너무 많으므로, 이 매뉴얼류에서도 로마숫자 대제목만
    // 경계로 인정한다. 그마저도 "제N조" 형식을 쓰는 절대다수 문서에는 전혀 적용하지 않고, ARTICLE_PATTERN/
    // ADDENDUM_PATTERN이 경계를 하나도 못 찾은 문서에 한해서만 2차 폴백으로 시도한다(로마숫자가 다른 의미로
    // 쓰이는 문서에 영향이 가지 않도록 범위를 최대한 좁힘).
    private static final Pattern ROMAN_NUMERAL_PATTERN = Pattern.compile("(?m)^[Ⅰ-Ⅹ]+\\.\\s*\\S");

    // 이런 매뉴얼의 목차는 2단 레이아웃이라 "Ⅰ. 담보평가                    2. 거래의 개설"처럼 같은 줄에
    // 다른 항목이 이어붙는 경우가 많다. 본문의 진짜 대제목은 제목만 있고 그 줄이 끝나므로, 매치된 줄에 공백
    // 4칸 이상 뒤에 다른 문자가 더 있으면 목차의 병합된 줄로 보고 제외한다(완벽하진 않지만 대부분을 걸러낸다).
    private static final Pattern MULTI_COLUMN_TOC_GAP = Pattern.compile("\\S\\s{4,}\\S");

    /**
     * 토큰 개수가 아니라 "제N조"/"부칙" 경계로 먼저 나눈 뒤, 한 덩어리가 너무 길면(500토큰 초과)
     * {@link TokenTextSplitter}로 그 부분만 추가로 쪼갠다. 순수 토큰 기반 분할과 달리 조항 하나가
     * 통째로 한 청크에 들어가서, "조건은 있는데 예외 조항이 다른 청크로 잘려나가는" 문제를 줄인다.
     * <p>
     * PDF는 원래 페이지 단위(Document 1개=1페이지)로 들어오는데, 조항이 페이지 경계를 넘어갈 수 있어서
     * 여러 페이지 텍스트를 순서대로 이어붙인 뒤 그 전체 텍스트에서 경계를 찾는다. 각 청크의
     * page_number/end_page_number는 그 내용이 실제로 걸쳐있는 페이지 범위로 다시 계산해서 넣는다
     * (기존에 있던 필드를 그대로 활용 - PagePdfDocumentReader가 페이지당 1개 청크만 만들 때는
     * page_number == end_page_number였지만, 조항이 페이지를 넘기면 이제 진짜로 범위가 될 수 있다).
     * Word 문서는 애초에 페이지 개념이 없으므로(TikaDocumentReader가 문서 전체를 1개 Document로 읽음)
     * 페이지 메타데이터 없이 경계만 찾는다. "제N조" 패턴을 하나도 못 찾으면(문서가 이 형식을 안 따름)
     * 문서 전체를 하나로 취급해 예전과 동일하게 동작한다(안전한 폴백).
     */
    private List<Document> splitIntoChunks(List<Document> sourceDocuments) {
        if (sourceDocuments.isEmpty()) {
            return List.of();
        }

        StringBuilder fullTextBuilder = new StringBuilder();
        List<int[]> pageRanges = new ArrayList<>(); // {pageNumber, startOffset, endOffsetExclusive}
        boolean hasPageInfo = true;

        for (Document sourceDocument : sourceDocuments) {
            Object pageNumberValue = sourceDocument.getMetadata().get(PagePdfDocumentReader.METADATA_START_PAGE_NUMBER);
            int startOffset = fullTextBuilder.length();
            fullTextBuilder.append(sourceDocument.getText()).append('\n');
            int endOffset = fullTextBuilder.length();
            if (pageNumberValue instanceof Number number) {
                pageRanges.add(new int[]{number.intValue(), startOffset, endOffset});
            } else {
                hasPageInfo = false;
            }
        }
        String fullText = fullTextBuilder.toString();

        List<Integer> articleStarts = findBoundaries(ARTICLE_PATTERN, fullText, -1);
        // 부칙 항목은 목차에도 같은 글자로 나열되는 경우가 많은데("제1장 총칙 ... 부칙"처럼 번호 없이),
        // 목차는 항상 첫 조항보다 앞에 나오므로 첫 조항 시작 이전에 등장하는 "부칙"은 목차로 보고 제외한다.
        int firstArticleStart = articleStarts.isEmpty() ? -1 : articleStarts.get(0);
        List<Integer> addendumStarts = findBoundaries(ADDENDUM_PATTERN, fullText, firstArticleStart);

        TreeSet<Integer> mergedBoundaries = new TreeSet<>(articleStarts);
        mergedBoundaries.addAll(addendumStarts);
        if (mergedBoundaries.isEmpty()) {
            // "제N조" 형식을 전혀 안 쓰는 문서 - 로마숫자 대제목이라도 있으면 2차 폴백으로 시도한다.
            mergedBoundaries.addAll(findRomanNumeralBoundaries(fullText));
        }
        List<Integer> boundaries = new ArrayList<>(mergedBoundaries);

        List<Document> articleDocuments = new ArrayList<>();
        if (boundaries.isEmpty()) {
            addArticleDocument(articleDocuments, fullText, 0, fullText.length(), pageRanges, hasPageInfo);
        } else {
            if (boundaries.get(0) > 0) {
                // 제1조 앞의 서문/목차 등도 버리지 않고 별도 청크로 유지한다.
                addArticleDocument(articleDocuments, fullText, 0, boundaries.get(0), pageRanges, hasPageInfo);
            }
            for (int i = 0; i < boundaries.size(); i++) {
                int start = boundaries.get(i);
                int end = (i + 1 < boundaries.size()) ? boundaries.get(i + 1) : fullText.length();
                addArticleDocument(articleDocuments, fullText, start, end, pageRanges, hasPageInfo);
            }
        }

        // 조항 하나가 500토큰을 넘으면 여기서 문장부호 경계로 추가 분할되고, 넘지 않으면 그대로 한 청크로 반환된다
        // (Spring AI 공식 문서: "chunkSize보다 작거나 같은 텍스트는 그대로 청크 1개로 반환됨").
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(500)
                .withMinChunkSizeChars(200)
                .withMinChunkLengthToEmbed(10)
                .withKeepSeparator(true)
                .build();
        return splitter.apply(articleDocuments);
    }

    /**
     * 패턴에 매치되는 위치 중 목차의 점선 리더 항목(예: "제10조 (표제) ....... 3")과, minAllowedStart보다
     * 앞에 나오는 매치(목차에 번호 없이 나열된 "부칙" 등)를 제외하고 실제 경계 오프셋만 반환한다.
     */
    private List<Integer> findBoundaries(Pattern pattern, String fullText, int minAllowedStart) {
        List<Integer> starts = new ArrayList<>();
        Matcher matcher = pattern.matcher(fullText);
        while (matcher.find()) {
            if (matcher.start() < minAllowedStart) {
                continue;
            }
            if (isTableOfContentsLine(fullText, matcher.end())) {
                continue;
            }
            starts.add(matcher.start());
        }
        return starts;
    }

    private boolean isTableOfContentsLine(String fullText, int matchEnd) {
        int lineEnd = fullText.indexOf('\n', matchEnd);
        if (lineEnd < 0) {
            lineEnd = fullText.length();
        }
        return TOC_DOT_LEADER_SUFFIX.matcher(fullText.substring(matchEnd, lineEnd)).find();
    }

    private List<Integer> findRomanNumeralBoundaries(String fullText) {
        List<Integer> starts = new ArrayList<>();
        Matcher matcher = ROMAN_NUMERAL_PATTERN.matcher(fullText);
        while (matcher.find()) {
            int lineEnd = fullText.indexOf('\n', matcher.end());
            if (lineEnd < 0) {
                lineEnd = fullText.length();
            }
            String line = fullText.substring(matcher.start(), lineEnd);
            if (MULTI_COLUMN_TOC_GAP.matcher(line).find()) {
                continue;
            }
            starts.add(matcher.start());
        }
        return starts;
    }

    private void addArticleDocument(List<Document> target, String fullText, int start, int end,
            List<int[]> pageRanges, boolean hasPageInfo) {
        String text = fullText.substring(start, end).strip();
        if (text.isEmpty()) {
            return;
        }

        Map<String, Object> metadata = new HashMap<>();
        if (hasPageInfo && !pageRanges.isEmpty()) {
            metadata.put(PagePdfDocumentReader.METADATA_START_PAGE_NUMBER, findPageForOffset(pageRanges, start));
            metadata.put(PagePdfDocumentReader.METADATA_END_PAGE_NUMBER, findPageForOffset(pageRanges, Math.max(start, end - 1)));
        }
        target.add(new Document(text, metadata));
    }

    private int findPageForOffset(List<int[]> pageRanges, int offset) {
        for (int[] range : pageRanges) {
            if (offset >= range[1] && offset < range[2]) {
                return range[0];
            }
        }
        // 이어붙인 텍스트 끝의 개행 문자 등 범위를 살짝 벗어나는 오프셋은 마지막 페이지로 처리한다.
        return pageRanges.get(pageRanges.size() - 1)[0];
    }

    private void bulkIndex(List<Document> chunks, String fileName, String documentTitle, String category, Set<String> allowedRoles) {
        String ingestedAt = Instant.now().toString();
        List<float[]> embeddings = embedInBatches(chunks.stream().map(Document::getText).toList());

        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            Map<String, Object> esDocument = toElasticsearchDocument(
                    chunk, embeddings.get(i), fileName, documentTitle, category, allowedRoles, ingestedAt);
            String docId = UUID.randomUUID().toString();

            bulkBuilder.operations(op -> op.index(idx -> idx
                    .index(indexName)
                    .id(docId)
                    .document(esDocument)));
        }

        BulkResponse response;
        try {
            response = elasticsearchClient.bulk(bulkBuilder.build());
        } catch (IOException e) {
            throw new IllegalStateException("Elasticsearch에 문서를 적재하는 중 오류가 발생했습니다.", e);
        }

        if (response.errors()) {
            for (BulkResponseItem item : response.items()) {
                if (item.error() != null) {
                    log.error("청크 색인 실패: id={}, reason={}", item.id(), item.error().reason());
                }
            }
            throw new IllegalStateException("일부 청크가 Elasticsearch 색인에 실패했습니다. 로그를 확인하세요.");
        }
    }

    // Gemini 임베딩 API는 한 번의 배치 요청에 최대 100개 텍스트만 허용한다
    // ("BatchEmbedContentsRequest.requests: at most 100 requests can be in one batch" - 실제로 겪은 오류).
    // 500토큰 청크 기준 100개면 문서 하나가 꽤 커야 넘는 수준이지만, 규정집처럼 큰 문서는 실제로 넘는다.
    private static final int MAX_EMBEDDING_BATCH_SIZE = 100;

    private List<float[]> embedInBatches(List<String> texts) {
        if (texts.size() <= MAX_EMBEDDING_BATCH_SIZE) {
            return embeddingModel.embed(texts);
        }
        List<float[]> embeddings = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += MAX_EMBEDDING_BATCH_SIZE) {
            List<String> batch = texts.subList(i, Math.min(i + MAX_EMBEDDING_BATCH_SIZE, texts.size()));
            embeddings.addAll(embeddingModel.embed(batch));
        }
        return embeddings;
    }

    private Map<String, Object> toElasticsearchDocument(
            Document chunk, float[] embedding, String fileName, String documentTitle, String category,
            Set<String> allowedRoles, String ingestedAt) {

        // page_number/end_page_number는 PagePdfDocumentReader가 청크에 남긴 메타데이터에서만 채워진다.
        // Word 문서 청크(TikaDocumentReader)는 이 키가 없으므로 자연스럽게 null이 된다(페이지 개념 없음).
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("file_name", fileName);
        metadata.put("page_number", chunk.getMetadata().get(PagePdfDocumentReader.METADATA_START_PAGE_NUMBER));
        metadata.put("end_page_number", chunk.getMetadata().get(PagePdfDocumentReader.METADATA_END_PAGE_NUMBER));
        metadata.put("document_title", documentTitle);
        metadata.put("category", category);
        metadata.put("allowed_roles", allowedRoles);
        metadata.put("ingested_at", ingestedAt);

        Map<String, Object> esDocument = new HashMap<>();
        esDocument.put("content", chunk.getText());
        esDocument.put("embedding", embedding);
        esDocument.put("metadata", metadata);
        return esDocument;
    }

    private Resource toResource(MultipartFile file) {
        try {
            return new InputStreamResource(file.getInputStream()) {
                @Override
                public String getFilename() {
                    return file.getOriginalFilename();
                }
            };
        } catch (IOException e) {
            throw new IllegalStateException("업로드된 파일을 읽는 중 오류가 발생했습니다.", e);
        }
    }

    private Resource toResource(byte[] content, String fileName) {
        return new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
    }

    private record IngestedChunks(int sourceUnitCount, int chunkCount) {
    }

    public record IngestionResult(String documentTitle, int pageCount, int chunkCount, String indexName) {
    }

    public record BatchIngestionResult(List<BatchItemResult> succeeded, List<SkippedEntry> skipped) {
    }

    public record BatchItemResult(String entryName, String documentTitle, String category, int chunkCount) {
    }

    public record SkippedEntry(String entryName, String reason) {
    }
}
