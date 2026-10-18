package com.itajay.superassistant.checkpoint;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Drops a thread's graph state after an aborted run.
 *
 * <p>Disposing a Reactor stream can stop the graph between a model's tool call and
 * its tool result. The checkpoint written by that superstep is then not a valid
 * conversation prefix: reusing the thread made DeepSeek reject the next request
 * with HTTP 400. The visible transcript lives in {@code custom_chat_memory} (and
 * the frontend), so resetting the graph state only drops the agent's hidden
 * continuation context — it keeps the thread usable.</p>
 */
@Service
public class ThreadStateResetService {

    private static final Logger log = LoggerFactory.getLogger(ThreadStateResetService.class);

    private final JdbcTemplate jdbcTemplate;
    private final MysqlSaver mysqlSaver;

    public ThreadStateResetService(JdbcTemplate jdbcTemplate, MysqlSaver mysqlSaver) {
        this.jdbcTemplate = jdbcTemplate;
        this.mysqlSaver = mysqlSaver;
    }

    public void reset(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return;
        }
        try {
            // Evict the in-memory copy first, otherwise the next turn can still be
            // served from MemorySaver's process-local list.
            mysqlSaver.release(RunnableConfig.builder().threadId(threadId).build());
        } catch (Exception e) {
            log.warn("Could not release in-memory checkpoints for cancelled thread {}: {}",
                    threadId, e.getMessage());
        }
        try {
            // GRAPH_THREAD.thread_id is a saver-generated UUID; the conversation id
            // lives in thread_name. Delete by both so a restart cannot load the
            // half-written state back.
            var ids = jdbcTemplate.query(
                    "SELECT thread_id FROM GRAPH_THREAD WHERE thread_name = ? OR thread_id = ?",
                    (rs, rowNum) -> rs.getString(1), threadId, threadId);
            int checkpoints = 0;
            for (String id : ids) {
                checkpoints += jdbcTemplate.update(
                        "DELETE FROM GRAPH_CHECKPOINT WHERE thread_id = ?", id);
            }
            int threads = jdbcTemplate.update(
                    "DELETE FROM GRAPH_THREAD WHERE thread_name = ? OR thread_id = ?",
                    threadId, threadId);
            log.info("Reset cancelled thread state [thread={}, checkpoints={}, threads={}]",
                    threadId, checkpoints, threads);
        } catch (Exception e) {
            log.warn("Could not delete checkpoints for cancelled thread {}: {}",
                    threadId, e.getMessage());
        }
    }
}
