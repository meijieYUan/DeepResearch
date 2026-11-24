package com.itajay.superassistant.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The adapter that turns {@code call()} into "stream, then aggregate".
 *
 * <p>Its correctness matters more than it looks: every sub-agent's tool arguments —
 * a whole analysis file, a whole document — arrive as streamed deltas. If the
 * aggregation dropped or split a delta, the tool call would receive truncated JSON and
 * fail with {@code Unexpected end-of-input}, which reads like the model losing its mind
 * rather than a transport bug.</p>
 */
class ModelTransportConfigTest {

    private static ChatResponse chunk(String text, List<AssistantMessage.ToolCall> toolCalls) {
        return new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content(text).toolCalls(toolCalls).build())));
    }

    private static ChatModel streaming(ChatResponse... chunks) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException("the adapter must not delegate call()");
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(chunks);
            }
        };
    }

    @Test
    void toolCallArgumentsSplitAcrossChunksAreReassembled() {
        var adapter = new ModelTransportConfig.CallViaStreamChatModel(streaming(
                chunk("", List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "writePaperAnalysis", "{\"content\":\"前半段"))),
                chunk("", List.of(new AssistantMessage.ToolCall(
                        null, null, null, "后半段\"}")))));

        ChatResponse response = adapter.call(new Prompt("x"));

        List<AssistantMessage.ToolCall> calls = response.getResult().getOutput().getToolCalls();
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).name()).isEqualTo("writePaperAnalysis");
        assertThat(calls.get(0).arguments()).isEqualTo("{\"content\":\"前半段后半段\"}");
    }

    @Test
    void streamedTextIsConcatenated() {
        var adapter = new ModelTransportConfig.CallViaStreamChatModel(streaming(
                chunk("你", List.of()), chunk("好", List.of())));

        ChatResponse response = adapter.call(new Prompt("x"));

        assertThat(response.getResult().getOutput().getText()).isEqualTo("你好");
    }

    @Test
    void anEmptyStreamFailsLoudlyInsteadOfReturningBlank() {
        var adapter = new ModelTransportConfig.CallViaStreamChatModel(streaming());

        assertThatThrownBy(() -> adapter.call(new Prompt("x")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void streamPassesThroughUntouched() {
        ChatResponse first = chunk("a", List.of());
        var adapter = new ModelTransportConfig.CallViaStreamChatModel(streaming(first));

        assertThat(adapter.stream(new Prompt("x")).collectList().block())
                .containsExactly(first);
    }
}
