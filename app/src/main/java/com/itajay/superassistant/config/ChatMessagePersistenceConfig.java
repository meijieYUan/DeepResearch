package com.itajay.superassistant.config;

import com.itajay.superassistant.rag.CustomJdbcChatMemoryRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class ChatMessagePersistenceConfig {

    @Bean
    public CustomJdbcChatMemoryRepository customJdbcChatMemoryRepository(
            @Qualifier("dataSource") DataSource dataSource) {
        return CustomJdbcChatMemoryRepository.builder()
                .dataSource(dataSource)
                .build();
    }
}
