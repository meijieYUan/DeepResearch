package com.itajay.superassistant.config;

import com.itajay.superassistant.rag.OptionalMilvusVectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 向量库装配。
 *
 * <p>向量检索依赖 Embedding 模型与 Milvus，两者都是可选能力：
 * {@link OptionalMilvusVectorStore} 在首次真正使用前不创建 Milvus 客户端，
 * 也不解析 {@link EmbeddingModel}。因此未配置 {@code EMBEDDING_KEY}、Milvus
 * 未启动或 Embedding 服务不可用时，应用仍能正常启动，RAG 自动退化为 BM25
 * 关键字召回。</p>
 */
@Configuration
public class VectorConfig {

    /**
     * {@code destroyMethod = "close"} releases the lazily created gRPC channel on shutdown;
     * without it the client would outlive the context.
     */
    @Bean(destroyMethod = "close")
    public VectorStore vectorStore(
            ObjectProvider<EmbeddingModel> embeddingModel,
            @Value("${spring.ai.dashscope.api-key:}") String embeddingApiKey,
            @Value("${rag.vector.milvus.host:127.0.0.1}") String milvusHost,
            @Value("${rag.vector.milvus.port:9090}") int milvusPort) {
        return new OptionalMilvusVectorStore(embeddingModel, embeddingApiKey, milvusHost, milvusPort);
    }
}
