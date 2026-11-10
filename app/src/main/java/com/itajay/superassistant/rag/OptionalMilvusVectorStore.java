package com.itajay.superassistant.rag;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 可降级的 Milvus 向量库：向量能力是可选能力，不影响主进程启动与运行。
 *
 * <p>启动阶段不创建 Milvus 客户端、也不解析 {@link EmbeddingModel}。只有真正发生
 * 向量写入/检索时才懒初始化：</p>
 * <ul>
 *   <li>{@code EMBEDDING_KEY} 未配置 → 直接抛出可捕获的异常，调用方跳过向量路径；</li>
 *   <li>Embedding / Milvus 不可达 → 同样降级，RAG 侧继续使用 BM25 关键字召回；</li>
 *   <li>服务恢复后，下次调用会重新尝试初始化，不需要重启应用。</li>
 * </ul>
 *
 * <p>此前 {@code MilvusServiceClient} 是启动期 Bean，Milvus 未启动时直接让整个
 * Spring 上下文失败；这层包装把该依赖推迟到按需阶段。</p>
 */
public class OptionalMilvusVectorStore implements VectorStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OptionalMilvusVectorStore.class);

    /** 同类失败最多每分钟告警一次，避免每次检索都刷屏。 */
    private static final long FAILURE_WARN_INTERVAL_MS = 60_000L;

    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final String embeddingApiKey;
    private final String milvusHost;
    private final int milvusPort;

    private volatile VectorStore delegate;
    /** Held separately so a failed schema initialisation can still release the gRPC channel. */
    private volatile MilvusServiceClient milvusClient;
    private volatile long lastFailureWarnAt;

    public OptionalMilvusVectorStore(ObjectProvider<EmbeddingModel> embeddingModelProvider,
                                     String embeddingApiKey,
                                     String milvusHost,
                                     int milvusPort) {
        this.embeddingModelProvider = embeddingModelProvider;
        this.embeddingApiKey = embeddingApiKey;
        this.milvusHost = milvusHost;
        this.milvusPort = milvusPort;
    }

    @Override
    public void add(List<Document> documents) {
        delegate().add(documents);
    }

    @Override
    public void delete(List<String> idList) {
        delegate().delete(idList);
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        delegate().delete(filterExpression);
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        return delegate().similaritySearch(request);
    }

    private VectorStore delegate() {
        VectorStore current = delegate;
        if (current != null) {
            return current;
        }
        if (embeddingApiKey == null || embeddingApiKey.isBlank()) {
            // 未配置向量模型：这是合法降级状态，不是故障。
            throw new IllegalStateException("EMBEDDING_KEY 未配置，向量检索与写入已禁用");
        }

        synchronized (this) {
            if (delegate != null) {
                return delegate;
            }
            MilvusServiceClient client = null;
            try {
                // getIfUnique (not getIfAvailable): a second EmbeddingModel bean must be a
                // diagnostic, not a confusing "NoUniqueBeanDefinitionException" out of here.
                EmbeddingModel embeddingModel = embeddingModelProvider.getIfUnique();
                if (embeddingModel == null) {
                    throw new IllegalStateException("EmbeddingModel Bean 不存在");
                }

                ConnectParam connectParam = ConnectParam.newBuilder()
                        .withHost(milvusHost)
                        .withPort(milvusPort)
                        // 不可达时快速失败，避免一次检索长时间卡住
                        .withConnectTimeout(2, TimeUnit.SECONDS)
                        .withRpcDeadline(10, TimeUnit.SECONDS)
                        .build();

                client = new MilvusServiceClient(connectParam);
                MilvusVectorStore store = MilvusVectorStore
                        .builder(client, embeddingModel)
                        // 默认 false 时 afterPropertiesSet() 直接 return，collection 永远不会
                        // 被创建，写入会在第一次 insert 时失败。这里必须显式打开。
                        .initializeSchema(true)
                        .build();
                // 非 Spring 管理的实例，需手动走一次初始化钩子（对应上方的 initializeSchema）。
                store.afterPropertiesSet();

                milvusClient = client;
                delegate = store;
                log.info("Milvus 向量库已启用：{}:{}", milvusHost, milvusPort);
                return store;
            } catch (Exception e) {
                // A client built before the failure would otherwise leak its gRPC channel.
                closeQuietly(client);
                warnThrottled("Milvus/Embedding 不可用，本次跳过向量写入/检索（RAG 继续使用 BM25）：{}", e);
                throw new IllegalStateException("向量库不可用: " + e.getMessage(), e);
            }
        }
    }

    /** Releases the gRPC channel. Called by Spring on context shutdown. */
    @Override
    public void close() {
        synchronized (this) {
            delegate = null;
            closeQuietly(milvusClient);
            milvusClient = null;
        }
    }

    private static void closeQuietly(MilvusServiceClient client) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (Exception e) {
            log.debug("关闭 Milvus 客户端失败（忽略）", e);
        }
    }

    private void warnThrottled(String message, Throwable e) {
        long now = System.currentTimeMillis();
        if (now - lastFailureWarnAt >= FAILURE_WARN_INTERVAL_MS) {
            lastFailureWarnAt = now;
            log.warn(message, e.toString());
            log.debug("向量库不可用详情", e);
        } else {
            log.debug(message, e.toString());
        }
    }
}
