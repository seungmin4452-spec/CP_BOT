package com.sunjin.CP_BOT.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/**
 * spring.ai.vectorstore.elasticsearch.initialize-schema=false 로 꺼둔 자동 스키마 생성을 대신해,
 * RBAC 메타데이터(allowed_roles)와 한국어 형태소 분석기(nori)를 포함한 커스텀 매핑으로
 * 애플리케이션 기동 시 인덱스를 직접 생성한다.
 */
@Slf4j
@Component
public class ElasticsearchIndexInitializer implements ApplicationRunner {

    private static final String INDEX_MAPPING_RESOURCE_PATH = "elasticsearch/company-policy-index-mapping.json";

    private final ElasticsearchClient elasticsearchClient;
    private final String indexName;

    public ElasticsearchIndexInitializer(
            ElasticsearchClient elasticsearchClient,
            @Value("${spring.ai.vectorstore.elasticsearch.index-name}") String indexName) {
        this.elasticsearchClient = elasticsearchClient;
        this.indexName = indexName;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        boolean indexExists = elasticsearchClient.indices().exists(e -> e.index(indexName)).value();
        if (indexExists) {
            log.info("Elasticsearch 인덱스 '{}' 가 이미 존재합니다. 초기화를 건너뜁니다.", indexName);
            return;
        }

        ClassPathResource mappingResource = new ClassPathResource(INDEX_MAPPING_RESOURCE_PATH);
        try (InputStream inputStream = mappingResource.getInputStream()) {
            elasticsearchClient.indices().create(c -> c.index(indexName).withJson(inputStream));
        }
        log.info("Elasticsearch 인덱스 '{}' 를 커스텀 매핑(nori 분석기 + RBAC 메타데이터)으로 생성했습니다.", indexName);
    }
}
