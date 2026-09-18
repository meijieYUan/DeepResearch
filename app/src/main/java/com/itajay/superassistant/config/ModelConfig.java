package com.itajay.superassistant.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ModelConfig {

    @Value("${spring.ai.deepseek.api-key}")
    private String apiKey;

    @Value("${spring.ai.deepseek.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${spring.ai.deepseek.chat.options.model:deepseek-chat}")
    private String model;

    /**
     * Output ceiling for every agent, pinned explicitly rather than left to the
     * provider default.
     *
     * <p>This is not a fix for the truncation the research workflow hits. The writer
     * emits the whole document as one {@code writeResearchDocument} argument, so once
     * a document outgrows the ceiling the tool call is cut off mid-string and the
     * framework fails to parse it ("Unexpected end-of-input ... column: 23400"),
     * aborting the run. 8192 is DeepSeek's maximum, so no value here removes that
     * limit — documents that size have to be written in sections instead. Pinning it
     * at least makes the ceiling visible and adjustable in one place.</p>
     */
    @Value("${spring.ai.deepseek.chat.options.max-tokens:8192}")
    private Integer maxTokens;

    @Bean
    public DeepSeekApi deepSeekApi() {
        return DeepSeekApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();
    }

    @Bean
    public ObservationRegistry observationRegistry() {
        return ObservationRegistry.create();
    }

    @Bean
    public ChatModel chatModel(DeepSeekApi deepSeekApi,
                                ToolCallingManager toolCallingManager,
                                ObservationRegistry observationRegistry) {
        return DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .defaultOptions(DeepSeekChatOptions.builder()
                        .model(model)
                        .temperature(0.7)
                        .maxTokens(maxTokens)
                        .build())
                .toolCallingManager(toolCallingManager)
                .observationRegistry(observationRegistry)
                .build();
    }

    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.create(chatModel);
    }
}
