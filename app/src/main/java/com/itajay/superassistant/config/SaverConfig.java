package com.itajay.superassistant.config;

import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Wires {@link MysqlSaver} to the application's DataSource.
 *
 * <p>The DataSource itself is the Spring Boot auto-configured one, driven by
 * {@code spring.datasource.*} in application.yml (password via {@code DB_PASSWORD}).
 * Hand-built DataSource beans used to live here with hard-coded credentials; they
 * shadowed the yml configuration entirely, so the yml block was dead config and
 * changing the database meant recompiling. The second (rag) DataSource was never
 * injected anywhere and is gone.</p>
 */
@Configuration
public class SaverConfig {

    @Bean
    public MysqlSaver mysqlSaver(DataSource dataSource) {
        return MysqlSaver
                .builder()
                .dataSource(dataSource)
                .build();
    }
}
