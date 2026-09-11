package com.itajay.superassistant.checkpoint;

import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.itajay.superassistant.config.CheckpointRetentionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Keeps graph checkpoints bounded without breaking thread resume.
 *
 * A thread is deleted only after its newest checkpoint has been idle beyond
 * the retention window. Non-expired, inactive threads keep their newest N
 * checkpoints; active threads are left untouched until they become idle.
 */
@Service
public class CheckpointRetentionService {

    private static final Logger log = LoggerFactory.getLogger(CheckpointRetentionService.class);

    private static final String FIND_THREADS_SQL = """
            SELECT t.thread_id, t.thread_name, MAX(c.saved_at) AS latest_saved_at
            FROM GRAPH_THREAD t
            LEFT JOIN GRAPH_CHECKPOINT c ON c.thread_id = t.thread_id
            GROUP BY t.thread_id, t.thread_name
            """;

    private static final String DELETE_THREAD_SQL =
            "DELETE FROM GRAPH_THREAD WHERE thread_id = ?";

    private static final String TRIM_THREAD_SQL = """
            DELETE c FROM GRAPH_CHECKPOINT c
            WHERE c.thread_id = ? AND c.checkpoint_id IN (
                SELECT checkpoint_id FROM (
                    SELECT checkpoint_id,
                           ROW_NUMBER() OVER (
                               ORDER BY saved_at DESC, checkpoint_id DESC
                           ) AS row_no
                    FROM GRAPH_CHECKPOINT
                    WHERE thread_id = ?
                ) ranked
                WHERE row_no > ?
            )
            """;

    private static final String INDEX_COUNT_SQL = """
            SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'GRAPH_CHECKPOINT'
              AND INDEX_NAME = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final MysqlSaver mysqlSaver;
    private final CheckpointRetentionProperties properties;

    public CheckpointRetentionService(JdbcTemplate jdbcTemplate,
                                       MysqlSaver mysqlSaver,
                                       CheckpointRetentionProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.mysqlSaver = mysqlSaver;
        this.properties = properties;
    }

    @Scheduled(cron = "${agent.checkpoint-retention.cron:0 0 3 * * *}")
    public void cleanup() {
        CleanupResult result = cleanupOnce();
        if (result.deletedThreads() > 0 || result.trimmedCheckpoints() > 0) {
            log.info("Checkpoint retention: deleted {} thread(s), trimmed {} checkpoint(s)",
                    result.deletedThreads(), result.trimmedCheckpoints());
        } else {
            log.debug("Checkpoint retention: nothing to clean");
        }
    }

    public CleanupResult cleanupOnce() {
        ensureIndexes();

        Instant now = Instant.now();
        Instant retentionCutoff = now.minusSeconds(daysToSeconds(properties.getRetentionDays()));
        Instant activeCutoff = now.minusSeconds(hoursToSeconds(properties.getActiveGraceHours()));

        int deletedThreads = 0;
        int trimmedCheckpoints = 0;

        for (ThreadActivity activity : loadThreadActivities()) {
            if (activity.latestSavedAt() == null
                    || activity.latestSavedAt().toInstant().isBefore(retentionCutoff)) {
                deletedThreads += deleteThread(activity);
                continue;
            }

            if (activity.latestSavedAt().toInstant().isBefore(activeCutoff)) {
                trimmedCheckpoints += trimThread(activity);
            }
        }

        return new CleanupResult(deletedThreads, trimmedCheckpoints);
    }

    private List<ThreadActivity> loadThreadActivities() {
        return jdbcTemplate.query(FIND_THREADS_SQL, (rs, rowNum) -> new ThreadActivity(
                rs.getString("thread_id"),
                rs.getString("thread_name"),
                rs.getTimestamp("latest_saved_at")));
    }

    private int deleteThread(ThreadActivity activity) {
        evictMemoryCache(activity);
        int deleted = jdbcTemplate.update(DELETE_THREAD_SQL, activity.threadId());
        if (deleted > 0) {
            log.debug("Deleted expired checkpoint thread {} ({})",
                    activity.threadName(), activity.threadId());
        }
        return deleted;
    }

    private int trimThread(ThreadActivity activity) {
        int trimmed = jdbcTemplate.update(TRIM_THREAD_SQL,
                activity.threadId(), activity.threadId(), properties.getMaxPerThread());
        if (trimmed > 0) {
            evictMemoryCache(activity);
            log.debug("Trimmed {} checkpoint(s) for thread {}",
                    trimmed, activity.threadName());
        }
        return trimmed;
    }

    /**
     * MemorySaver keeps a process-local list beside MysqlSaver's database rows.
     * The graph framework exposes release(), which both evicts that cache and
     * marks the database thread released. Cleanup releases the thread first;
     * expired threads are deleted, while trimmed threads are reactivated after
     * cache eviction so future turns can resume from the retained rows.
     */
    private void evictMemoryCache(ThreadActivity activity) {
        try {
            mysqlSaver.release(RunnableConfig.builder()
                    .threadId(activity.threadName())
                    .build());
            jdbcTemplate.update(
                    "UPDATE GRAPH_THREAD SET is_released = FALSE WHERE thread_id = ?",
                    activity.threadId());
        } catch (Exception e) {
            log.warn("Failed to evict in-memory checkpoints for thread {}",
                    activity.threadName(), e);
        }
    }

    private void ensureIndexes() {
        createIndexIfMissing("IDX_GRAPH_CHECKPOINT_THREAD_SAVED",
                "CREATE INDEX IDX_GRAPH_CHECKPOINT_THREAD_SAVED "
                        + "ON GRAPH_CHECKPOINT (thread_id, saved_at)");
        createIndexIfMissing("IDX_GRAPH_CHECKPOINT_SAVED_AT",
                "CREATE INDEX IDX_GRAPH_CHECKPOINT_SAVED_AT "
                        + "ON GRAPH_CHECKPOINT (saved_at)");
    }

    private void createIndexIfMissing(String indexName, String createSql) {
        Integer count = jdbcTemplate.queryForObject(
                INDEX_COUNT_SQL, Integer.class, indexName);
        if (count != null && count > 0) {
            return;
        }
        try {
            jdbcTemplate.execute(createSql);
        } catch (DataAccessException e) {
            if (isDuplicateIndex(e)) {
                log.debug("Checkpoint index {} was created concurrently", indexName);
                return;
            }
            throw e;
        }
    }

    private static boolean isDuplicateIndex(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLException sqlException
                    && sqlException.getErrorCode() == 1061) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static long daysToSeconds(int days) {
        return days * 24L * 3600L;
    }

    private static long hoursToSeconds(int hours) {
        return hours * 3600L;
    }

    public record ThreadActivity(String threadId, String threadName, Timestamp latestSavedAt) {}

    public record CleanupResult(int deletedThreads, int trimmedCheckpoints) {}
}
