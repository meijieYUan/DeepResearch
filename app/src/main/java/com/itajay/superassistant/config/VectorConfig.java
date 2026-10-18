package com.itajay.superassistant.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class VectorConfig {
    @Bean
    public VectorStore vectorStore(MilvusServiceClient milvusServiceClient, EmbeddingModel embeddingModel){
        return MilvusVectorStore
                .builder(milvusServiceClient, embeddingModel)
                // Spring AI 2.0's builder defaults this to false; without it the
                // collection is never created and every RAG upload fails with
                // "can't find collection[vector_store]".
                .initializeSchema(true)
                .build();
    }

    @Bean
    public MilvusServiceClient milvusServiceClient(
            @org.springframework.beans.factory.annotation.Value("${rag.milvus.host:localhost}") String host,
            @org.springframework.beans.factory.annotation.Value("${rag.milvus.port:9090}") int port) {
        ConnectParam build = ConnectParam.newBuilder()
                .withHost(host)
                .withPort(port)
                .build();

        return new MilvusServiceClient(build);
    }
}
