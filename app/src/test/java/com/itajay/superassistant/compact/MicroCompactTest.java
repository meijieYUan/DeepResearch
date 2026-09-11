package com.itajay.superassistant.compact;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MicroCompactTest {

    @Test
    void replacesOnlyOldToolResultPayloads() {
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            messages.add(new UserMessage("user request " + i));
            messages.add(toolCall("call-" + i, "Read", "{\"path\":\"file-" + i + "\"}"));
            messages.add(toolResponse("call-" + i, "Read", "payload " + i));
        }

        List<Message> compacted = MicroCompact.compact(messages);

        assertThat(compacted).hasSize(messages.size() + 1);
        assertThat(compacted.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(compacted.get(0).getText()).contains("MicroCompact");

        long userMessages = compacted.stream()
                .filter(message -> message.getMessageType()
                        == org.springframework.ai.chat.messages.MessageType.USER)
                .count();
        assertThat(userMessages).isEqualTo(7);

        List<ToolResponseMessage> responses = compacted.stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .toList();
        assertThat(responses).hasSize(7);
        assertThat(responses.get(0).getResponses().get(0).responseData())
                .startsWith("[MicroCompact:");
        assertThat(responses.get(1).getResponses().get(0).responseData())
                .startsWith("[MicroCompact:");
        for (int i = 2; i < responses.size(); i++) {
            assertThat(responses.get(i).getResponses().get(0).responseData())
                    .isEqualTo("payload " + i);
        }
    }

    @Test
    void keepsMessageListWhenFewRecentTransactions() {
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            messages.add(new UserMessage("request " + i));
            messages.add(toolCall("call-" + i, "Read", "{}"));
            messages.add(toolResponse("call-" + i, "Read", "payload " + i));
        }

        List<Message> compacted = MicroCompact.compact(messages);

        assertThat(compacted).isSameAs(messages);
    }

    private static AssistantMessage toolCall(String id, String name, String arguments) {
        return AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        id, "function", name, arguments)))
                .build();
    }

    private static ToolResponseMessage toolResponse(String id, String name, String data) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(id, name, data)))
                .build();
    }
}
