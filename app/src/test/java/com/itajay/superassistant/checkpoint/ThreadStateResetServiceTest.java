package com.itajay.superassistant.checkpoint;

import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ThreadStateResetServiceTest {

    @Test
    void deletesCheckpointsByConversationNameNotJustSaverUuid() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:reset-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1"));
        jdbc.execute("CREATE TABLE GRAPH_THREAD (thread_id VARCHAR(36) PRIMARY KEY, thread_name VARCHAR(255))");
        jdbc.execute("CREATE TABLE GRAPH_CHECKPOINT (thread_id VARCHAR(36), checkpoint_id VARCHAR(36))");
        jdbc.update("INSERT INTO GRAPH_THREAD VALUES ('saver-uuid-1', 'conv-1')");
        jdbc.update("INSERT INTO GRAPH_CHECKPOINT VALUES ('saver-uuid-1', 'cp-1')");
        jdbc.update("INSERT INTO GRAPH_THREAD VALUES ('saver-uuid-2', 'conv-2')");
        jdbc.update("INSERT INTO GRAPH_CHECKPOINT VALUES ('saver-uuid-2', 'cp-2')");

        new ThreadStateResetService(jdbc, mock(MysqlSaver.class)).reset("conv-1");

        assertThat(jdbc.query("SELECT thread_id FROM GRAPH_THREAD",
                (rs, rowNum) -> rs.getString(1))).containsExactly("saver-uuid-2");
        assertThat(jdbc.query("SELECT checkpoint_id FROM GRAPH_CHECKPOINT",
                (rs, rowNum) -> rs.getString(1))).containsExactly("cp-2");
    }
}
