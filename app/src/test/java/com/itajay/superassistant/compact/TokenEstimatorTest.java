package com.itajay.superassistant.compact;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TokenEstimatorTest {

    @Test
    void countsChineseAndEnglishTextLocally() {
        TokenEstimator estimator = new BpeTokenEstimator();

        int chinese = estimator.estimate("上下文压缩");
        int english = estimator.estimate("context compaction");

        assertThat(chinese).isPositive();
        assertThat(english).isPositive();
    }

    @Test
    void includesToolCallAndToolResultPayloads() {
        TokenEstimator estimator = new BpeTokenEstimator();
        AssistantMessage call = AssistantMessage.builder()
                .content("Read the file.")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "Read",
                        "{\"path\":\"src/main/java/Example.java\"}")))
                .build();
        ToolResponseMessage response = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call-1", "Read", "public class Example {}")))
                .build();

        int textOnly = estimator.estimate("Read the file.");
        int callEstimate = estimator.estimate(call);
        int responseEstimate = estimator.estimate(response);

        assertThat(callEstimate).isGreaterThan(textOnly);
        assertThat(responseEstimate).isPositive();
    }
}
