package com.itajay.superassistant.rag;

import jakarta.annotation.PreDestroy;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 基于 Lucene 的 BM25 关键字索引。
 * <p>
 * 使用 {@link SmartChineseAnalyzer} 做中文分词，{@link BM25Similarity} 计算相关性，
 * 与向量库（Milvus）配合，构成"向量 + 关键字"的多路召回。
 * 索引通过 {@link FSDirectory} 落盘，应用重启后无需重新导入。
 * <p>
 * 读取走 {@link SearcherManager}（NRT）：检索不再每次新开 DirectoryReader，
 * 写入 commit 后 {@code maybeRefresh} 即可见；写入与检索不互斥。
 * 每个 chunk 记录来源文件名（{@link #SOURCE_METADATA_KEY}），同名文件重复导入时
 * 先 {@link #deleteBySource} 清掉旧 chunk，索引不再只增不减。
 */
@Component
public class Bm25Index {

    private static final Logger log = LoggerFactory.getLogger(Bm25Index.class);

    public static final String CONTENT_FIELD = "content";
    private static final String ID_FIELD = "id";
    private static final String SOURCE_FIELD = "source";

    /**
     * Metadata key carrying the source file name on each Spring AI Document.
     * Set by {@code RagService} at read time; survives the token splitter.
     */
    public static final String SOURCE_METADATA_KEY = "source_filename";

    private final Analyzer analyzer;
    private final FSDirectory directory;
    private final IndexWriter writer;
    private final SearcherManager searcherManager;

    public Bm25Index(@Value("${rag.retrieval.bm25.index-path:./data/bm25-index}") String indexPath) throws IOException {
        this.analyzer = new SmartChineseAnalyzer();
        Path path = Path.of(indexPath);
        this.directory = FSDirectory.open(path);
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        config.setSimilarity(new BM25Similarity());
        this.writer = new IndexWriter(directory, config);
        // NRT reader: sees committed + in-flight changes after maybeRefresh,
        // and is shared across searches instead of reopening the index per query.
        this.searcherManager = new SearcherManager(writer, null);
        log.info("BM25 index opened at {}", path.toAbsolutePath());
    }

    /**
     * 将文档分块写入 BM25 索引。来源文件名取自每个 chunk 的
     * {@link #SOURCE_METADATA_KEY} 元数据（可为 null）。
     *
     * @throws IOException 索引写入失败时上抛——静默吞掉会让向量库有、关键字库没有，
     *                     双写不一致且无从告警；调用方（RagService）负责降级与报警。
     */
    public synchronized void add(List<org.springframework.ai.document.Document> chunks) throws IOException {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        List<Document> luceneDocs = new ArrayList<>(chunks.size());
        for (org.springframework.ai.document.Document chunk : chunks) {
            String text = chunk.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            Document doc = new Document();
            doc.add(new StringField(ID_FIELD, UUID.randomUUID().toString(), Field.Store.YES));
            doc.add(new TextField(CONTENT_FIELD, text, Field.Store.YES));
            Object source = chunk.getMetadata() == null ? null : chunk.getMetadata().get(SOURCE_METADATA_KEY);
            if (source != null) {
                doc.add(new StringField(SOURCE_FIELD, String.valueOf(source), Field.Store.YES));
            }
            luceneDocs.add(doc);
        }
        if (luceneDocs.isEmpty()) {
            return;
        }
        writer.addDocuments(luceneDocs);
        writer.commit();
        searcherManager.maybeRefresh();
        log.debug("BM25 indexed {} chunks", luceneDocs.size());
    }

    /**
     * 删除某个来源文件的全部 chunk（重复导入同名文件时先清旧再写新）。
     *
     * @return 被删除的 chunk 数
     */
    public synchronized long deleteBySource(String sourceId) throws IOException {
        if (sourceId == null || sourceId.isBlank()) {
            return 0;
        }
        long deleted = writer.deleteDocuments(new Term(SOURCE_FIELD, sourceId));
        writer.commit();
        searcherManager.maybeRefresh();
        if (deleted > 0) {
            log.info("BM25 removed {} stale chunk(s) of source '{}'", deleted, sourceId);
        }
        return deleted;
    }

    /**
     * 关键字检索：用与索引一致的分析器对查询分词，构造 BooleanQuery（SHOULD）按 BM25 打分。
     * <p>不加锁：{@link SearcherManager} 自身线程安全，写入不再阻塞检索。
     *
     * @return 按 BM25 相关性降序的文档
     */
    public List<org.springframework.ai.document.Document> search(String queryText, int topK) {
        if (queryText == null || queryText.isBlank() || topK <= 0) {
            return List.of();
        }
        IndexSearcher searcher = null;
        try {
            Set<String> terms = analyzeQuery(queryText);
            if (terms.isEmpty()) {
                return List.of();
            }
            // Lucene hard-fails (TooManyClauses) past maxClauseCount; a long query used
            // to be swallowed into "no results". Cap instead, and say so in the log.
            int maxClauses = BooleanQuery.getMaxClauseCount();
            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            int added = 0;
            for (String term : terms) {
                if (added >= maxClauses) {
                    log.warn("BM25 query truncated to {} clauses (was {} terms)", maxClauses, terms.size());
                    break;
                }
                builder.add(new TermQuery(new Term(CONTENT_FIELD, term)), BooleanClause.Occur.SHOULD);
                added++;
            }
            BooleanQuery query = builder.build();

            searcher = searcherManager.acquire();
            searcher.setSimilarity(new BM25Similarity());
            TopDocs topDocs = searcher.search(query, topK);

            List<org.springframework.ai.document.Document> results = new ArrayList<>(topDocs.scoreDocs.length);
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document luceneDoc = searcher.storedFields().document(scoreDoc.doc);
                String text = luceneDoc.get(CONTENT_FIELD);
                if (text != null) {
                    results.add(new org.springframework.ai.document.Document(text));
                }
            }
            return results;
        } catch (Exception e) {
            log.warn("BM25 search failed for query '{}': {}", queryText, e.getMessage());
            return List.of();
        } finally {
            if (searcher != null) {
                try {
                    searcherManager.release(searcher);
                } catch (IOException e) {
                    log.warn("Failed to release BM25 searcher: {}", e.getMessage());
                }
            }
        }
    }

    private Set<String> analyzeQuery(String text) throws IOException {
        Set<String> terms = new LinkedHashSet<>();
        try (TokenStream stream = analyzer.tokenStream(CONTENT_FIELD, text)) {
            CharTermAttribute attr = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                terms.add(attr.toString());
            }
            stream.end();
        }
        return terms;
    }

    @PreDestroy
    public synchronized void close() {
        try {
            searcherManager.close();
            writer.close();
            directory.close();
        } catch (IOException e) {
            log.warn("Failed to close BM25 index", e);
        }
    }
}
