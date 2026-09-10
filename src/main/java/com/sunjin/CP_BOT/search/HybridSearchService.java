package com.sunjin.CP_BOT.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * BM25(content, nori 분석기)와 kNN(embedding) 검색을 각각 따로 실행한 뒤,
 * RRF(Reciprocal Rank Fusion) 공식으로 애플리케이션 코드에서 직접 두 결과를 융합한다.
 * <p>
 * Elasticsearch의 네이티브 {@code retriever.rrf}는 Platinum/Enterprise 라이선스 전용 기능이라
 * 우리가 쓰는 Basic(무료) 라이선스에서는 "security_exception: current license is non-compliant
 * for [Reciprocal Rank Fusion (RRF)]"로 거부된다(직접 확인, 2026-09-03). 반면 BM25 단독 쿼리와
 * kNN 단독 쿼리는 Basic 라이선스에서도 자유롭게 쓸 수 있으므로, RRF의 순위 융합 수식
 * {@code score(doc) = Σ 1 / (rank_constant + rank)} 만 Java로 재구현해 같은 효과를 무료로 낸다.
 * <p>
 * RBAC 사전 필터(metadata.allowed_roles)는 두 쿼리 모두에 각각 적용한다 - 필터가 걸리지 않은
 * 쿼리가 하나라도 있으면 그 결과만으로 접근 권한 없는 문서가 새어나갈 수 있기 때문이다.
 * <p>
 * BM25 쿼리와 (질의 임베딩 → kNN 쿼리)는 서로 의존관계가 없어 병렬로 실행한다({@link #search}) -
 * 순차 실행 대비 전체 검색 지연시간이 (BM25, 임베딩+kNN) 중 더 오래 걸리는 쪽 수준으로 줄어든다.
 */
@Service
@RequiredArgsConstructor
public class HybridSearchService {

    private static final String ADMIN_ROLE = "ADMIN";
    private static final int RANK_CONSTANT = 60;

    // spring-boot-starter-webmvc(Boot 4)는 spring-boot-starter-web(Boot 3)과 달리 ObjectMapper 빈을
    // 자동 등록하지 않는다. 여기서는 검색 요청 Map을 JSON으로 직렬화하는 용도로만 쓰므로 DI 없이 직접 생성한다.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // BM25 검색은 쿼리 임베딩이 필요 없고, kNN 검색은 임베딩(Gemini API 호출)이 끝나야 시작할 수 있다.
    // 원래는 임베딩 → BM25 → kNN을 순서대로 실행해 세 번의 네트워크 왕복이 그대로 합산됐는데, BM25는
    // 임베딩과 아무 의존관계가 없으므로 "BM25"와 "임베딩 → kNN"을 동시에 실행하면 전체 지연시간이
    // (BM25, 임베딩+kNN) 중 더 오래 걸리는 쪽으로 줄어든다. 둘 다 블로킹 I/O(ES/Gemini 호출)라 각 요청마다
    // 스레드를 새로 만들어도 비용이 거의 없는 가상 스레드를 썼다(Java 25).
    private static final ExecutorService SEARCH_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final ElasticsearchClient elasticsearchClient;
    private final EmbeddingModel embeddingModel;

    @Value("${spring.ai.vectorstore.elasticsearch.index-name}")
    private String indexName;

    public List<SearchResultItem> search(String queryText, Set<String> callerRoles, int topK) {
        int candidateSize = Math.max(topK * 5, 50);

        CompletableFuture<List<RankedHit>> bm25Future = CompletableFuture.supplyAsync(
                () -> runSearch(buildBm25Body(queryText, callerRoles, candidateSize)), SEARCH_EXECUTOR);

        CompletableFuture<List<RankedHit>> knnFuture = CompletableFuture
                .supplyAsync(() -> embeddingModel.embed(queryText), SEARCH_EXECUTOR)
                .thenApply(queryEmbedding -> buildKnnBody(queryEmbedding, callerRoles, candidateSize))
                .thenApply(this::runSearch);

        List<RankedHit> bm25Hits = join(bm25Future);
        List<RankedHit> knnHits = join(knnFuture);

        return fuseWithRrf(bm25Hits, knnHits, topK);
    }

    // CompletableFuture.join()은 원래 예외를 CompletionException으로 감싸버리는데, 그러면 runSearch()가
    // 던지는 IllegalStateException을 GlobalExceptionHandler가 더 이상 인식하지 못해 메시지 없는 500으로
    // 뭉개진다. 원래 예외(RuntimeException)를 그대로 꺼내 다시 던져서 병렬화 이전과 동일한 예외 처리 경로를 유지한다.
    private <T> T join(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            switch (e.getCause()) {
                case RuntimeException re -> throw re;
                case null, default -> throw e;
            }
        }
    }

    private Map<String, Object> buildBm25Body(String queryText, Set<String> callerRoles, int size) {
        Map<String, Object> query;
        if (callerRoles.contains(ADMIN_ROLE)) {
            query = Map.of("match", Map.of("content", queryText));
        } else {
            query = Map.of("bool", Map.of(
                    "must", Map.of("match", Map.of("content", queryText)),
                    "filter", Map.of("terms", Map.of("metadata.allowed_roles", List.copyOf(callerRoles)))));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("size", size);
        body.put("_source", List.of("content", "metadata"));
        return body;
    }

    private Map<String, Object> buildKnnBody(float[] queryEmbedding, Set<String> callerRoles, int size) {
        Map<String, Object> knn = new LinkedHashMap<>();
        knn.put("field", "embedding");
        knn.put("query_vector", queryEmbedding);
        knn.put("k", size);
        knn.put("num_candidates", Math.max(size * 2, 100));
        if (!callerRoles.contains(ADMIN_ROLE)) {
            knn.put("filter", Map.of("terms", Map.of("metadata.allowed_roles", List.copyOf(callerRoles))));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("knn", knn);
        body.put("size", size);
        body.put("_source", List.of("content", "metadata"));
        return body;
    }

    @SuppressWarnings("unchecked")
    private List<RankedHit> runSearch(Map<String, Object> requestBody) {
        try {
            String requestJson = OBJECT_MAPPER.writeValueAsString(requestBody);
            SearchRequest request = SearchRequest.of(b -> b
                    .index(indexName)
                    .withJson(new StringReader(requestJson)));

            SearchResponse<Map> response = elasticsearchClient.search(request, Map.class);
            List<RankedHit> hits = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                Map<String, Object> source = hit.source();
                if (source == null || hit.id() == null) {
                    continue;
                }
                hits.add(new RankedHit(hit.id(), source));
            }
            return hits;
        } catch (IOException e) {
            throw new IllegalStateException("검색 중 오류가 발생했습니다.", e);
        }
    }

    private List<SearchResultItem> fuseWithRrf(List<RankedHit> listA, List<RankedHit> listB, int topK) {
        Map<String, Double> fusedScores = new LinkedHashMap<>();
        Map<String, Map<String, Object>> sourceById = new LinkedHashMap<>();

        addRankScores(listA, fusedScores, sourceById);
        addRankScores(listB, fusedScores, sourceById);

        return fusedScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> toResultItem(entry.getKey(), entry.getValue(), sourceById.get(entry.getKey())))
                .toList();
    }

    private void addRankScores(List<RankedHit> hits, Map<String, Double> fusedScores, Map<String, Map<String, Object>> sourceById) {
        for (int i = 0; i < hits.size(); i++) {
            RankedHit hit = hits.get(i);
            int rank = i + 1; // RRF는 1부터 시작하는 순위를 사용한다.
            double rrfScore = 1.0 / (RANK_CONSTANT + rank);
            fusedScores.merge(hit.id(), rrfScore, Double::sum);
            sourceById.putIfAbsent(hit.id(), hit.source());
        }
    }

    @SuppressWarnings("unchecked")
    private SearchResultItem toResultItem(String id, double fusedScore, Map<String, Object> source) {
        Map<String, Object> metadata = (Map<String, Object>) source.get("metadata");
        return new SearchResultItem(
                (String) source.get("content"),
                metadata == null ? null : (String) metadata.get("document_title"),
                metadata == null ? null : (String) metadata.get("file_name"),
                metadata == null || metadata.get("page_number") == null
                        ? null : ((Number) metadata.get("page_number")).intValue(),
                fusedScore);
    }

    private record RankedHit(String id, Map<String, Object> source) {
    }

    public record SearchResultItem(
            String content,
            String documentTitle,
            String fileName,
            Integer pageNumber,
            Double score) {
    }
}
