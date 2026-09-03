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
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * PDF 규정 문서를 읽어 페이지 단위로 자른 뒤, 다시 의미 단위(Chunk)로 분할하고
 * Gemini 임베딩을 계산해 Elasticsearch에 적재한다.
 * <p>
 * Spring AI {@code Document}의 metadata는 String/int/float/boolean 값만 허용하기 때문에
 * (배열 불가) RBAC 필터링에 필요한 allowed_roles(List)는 Document metadata에 담지 않고,
 * 최종 Elasticsearch 색인 문서를 조립하는 단계에서 직접 추가한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestionService {

    private final EmbeddingModel embeddingModel;
    private final ElasticsearchClient elasticsearchClient;

    @Value("${spring.ai.vectorstore.elasticsearch.index-name}")
    private String indexName;

    public IngestionResult ingest(MultipartFile file, String documentTitle, String category, Set<String> allowedRoles) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("업로드된 PDF 파일이 비어 있습니다.");
        }
        if (allowedRoles == null || allowedRoles.isEmpty()) {
            throw new IllegalArgumentException("열람 권한(allowedRoles)이 지정되지 않은 문서는 적재할 수 없습니다.");
        }

        List<Document> pageDocuments = readPages(toResource(file));
        List<Document> chunks = splitIntoChunks(pageDocuments);

        bulkIndex(chunks, documentTitle, category, allowedRoles);

        log.info("PDF 적재 완료: title={}, pages={}, chunks={}, allowedRoles={}",
                documentTitle, pageDocuments.size(), chunks.size(), allowedRoles);
        return new IngestionResult(documentTitle, pageDocuments.size(), chunks.size(), indexName);
    }

    private List<Document> readPages(Resource pdfResource) {
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

    private List<Document> splitIntoChunks(List<Document> pageDocuments) {
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(500)
                .withMinChunkSizeChars(200)
                .withMinChunkLengthToEmbed(10)
                .withKeepSeparator(true)
                .build();
        return splitter.apply(pageDocuments);
    }

    private void bulkIndex(List<Document> chunks, String documentTitle, String category, Set<String> allowedRoles) {
        String ingestedAt = Instant.now().toString();
        List<float[]> embeddings = embeddingModel.embed(chunks.stream().map(Document::getText).toList());

        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            Map<String, Object> esDocument = toElasticsearchDocument(
                    chunk, embeddings.get(i), documentTitle, category, allowedRoles, ingestedAt);
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

    private Map<String, Object> toElasticsearchDocument(
            Document chunk, float[] embedding, String documentTitle, String category,
            Set<String> allowedRoles, String ingestedAt) {

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("file_name", chunk.getMetadata().get(PagePdfDocumentReader.METADATA_FILE_NAME));
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
            throw new IllegalStateException("업로드된 PDF를 읽는 중 오류가 발생했습니다.", e);
        }
    }

    public record IngestionResult(String documentTitle, int pageCount, int chunkCount, String indexName) {
    }
}
