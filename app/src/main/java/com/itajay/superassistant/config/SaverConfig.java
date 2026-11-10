package com.itajay.superassistant.config;

import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Wires {@link MysqlSaver} to the application's DataSource.
 *
 * <p>The DataSource is driven entirely by {@code spring.datasource.*} in
 * application.yml (password via {@code DB_PASSWORD}); no credentials are hard-coded.
 * It is declared explicitly rather than left to auto-configuration because Boot 4's
 * package move broke MyBatis-Plus 3.5.17's auto-configuration ordering — see
 * {@link #dataSource(DataSourceProperties)}.</p>
 */
@Configuration
public class SaverConfig {

    /**
     * 显式声明主 DataSource（仍完全由 {@code spring.datasource.*} 驱动）。
     *
     * <p>Spring Boot 4 把 {@code DataSourceAutoConfiguration} 移到了
     * {@code org.springframework.boot.jdbc.autoconfigure}，而 MyBatis-Plus 3.5.17
     * 的 {@code @AutoConfigureAfter} 仍指向 Boot 3 的旧包名。自动配置排序因此失效，
     * {@code MybatisPlusAutoConfiguration} 会先于 DataSource 创建做条件判断，
     * {@code @ConditionalOnSingleCandidate(DataSource.class)} 找不到 Bean，整条
     * MyBatis 自动配置被跳过，最终 Mapper 报 "sqlSessionFactory ... required"。
     *
     * <p>用户配置类先于自动配置解析，这里声明 DataSource 后，MyBatis 的条件判断
     * 就能看到它。配置值仍来自 {@link DataSourceProperties}，没有硬编码凭据。
     */
    @Bean
    public DataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    @Bean
    public MysqlSaver mysqlSaver(DataSource dataSource) {
        return MysqlSaver
                .builder()
                .dataSource(dataSource)
                .build();
    }
}
