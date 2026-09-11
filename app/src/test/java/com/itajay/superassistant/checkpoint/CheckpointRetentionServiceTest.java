package com.itajay.superassistant.checkpoint;

import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.itajay.superassistant.config.CheckpointRetentionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CheckpointRetentionServiceTest {

    private JdbcTemplate jdbcTemplate;
    private MysqlSaver saver;
    private CheckpointRetentionService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        saver = mock(MysqlSaver.class);
        CheckpointRetentionProperties properties = new CheckpointRetentionProperties();
        properties.setRetentionDays(30);
        properties.setMaxPerThread(20);
        properties.setActiveGraceHours(24);
        service = new CheckpointRetentionService(jdbcTemplate, saver, properties);

        when(jdbcTemplate.queryForObject(
                anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(1);
    }

    @Test
    void deletesExpiredThreadAndClearsMemoryCache() throws Exception {
        CheckpointRetentionService.ThreadActivity activity =
                new CheckpointRetentionService.ThreadActivity(
                "thread-uuid", "chat-1",
                Timestamp.from(Instant.now().minusSeconds(31L * 24 * 3600)));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
                .thenReturn(List.of(activity));
        when(jdbcTemplate.update(anyString(), eq("thread-uuid"))).thenReturn(1);

        CheckpointRetentionService.CleanupResult result = service.cleanupOnce();

        assertThat(result.deletedThreads()).isEqualTo(1);
        assertThat(result.trimmedCheckpoints()).isZero();
        verify(jdbcTemplate).update(
                eq("DELETE FROM GRAPH_THREAD WHERE thread_id = ?"), eq("thread-uuid"));
        verify(saver).release(any(RunnableConfig.class));
    }

    @Test
    void trimsInactiveThreadToConfiguredCheckpointLimit() throws Exception {
        CheckpointRetentionService.ThreadActivity activity =
                new CheckpointRetentionService.ThreadActivity(
                "thread-uuid", "chat-1",
                Timestamp.from(Instant.now().minusSeconds(2L * 24 * 3600)));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
                .thenReturn(List.of(activity));
        when(jdbcTemplate.update(anyString(), eq("thread-uuid"), eq("thread-uuid"), eq(20)))
                .thenReturn(17);

        CheckpointRetentionService.CleanupResult result = service.cleanupOnce();

        assertThat(result.deletedThreads()).isZero();
        assertThat(result.trimmedCheckpoints()).isEqualTo(17);
        verify(saver).release(any(RunnableConfig.class));
    }

    @Test
    void leavesActiveThreadUntouched() throws Exception {
        CheckpointRetentionService.ThreadActivity activity =
                new CheckpointRetentionService.ThreadActivity(
                "thread-uuid", "chat-1", Timestamp.from(Instant.now()));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
                .thenReturn(List.of(activity));

        CheckpointRetentionService.CleanupResult result = service.cleanupOnce();

        assertThat(result.deletedThreads()).isZero();
        assertThat(result.trimmedCheckpoints()).isZero();
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        verify(saver, never()).release(any(RunnableConfig.class));
    }
}
