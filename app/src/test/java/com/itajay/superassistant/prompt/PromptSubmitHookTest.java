package com.itajay.superassistant.prompt;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.itajay.superassistant.plan.PlanModeContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PromptSubmitHook is a ModelInterceptor: dynamic system prompts are merged into
 * the request's system message and must never touch the messages list.
 */
class PromptSubmitHookTest {

    private final PromptSubmitHook interceptor = new PromptSubmitHook();

    @AfterEach
    void clearPlanState() {
        PlanModeContext.setEnabled("t1", false);
    }

    @Test
    void nameIsStable() {
        assertThat(interceptor.getName()).isEqualTo("prompt_submit_hook");
    }

    @Test
    void injectsPlanModePromptIntoSystemMessage() {
        PlanModeContext.setEnabled("t1", true);

        ModelRequest original = ModelRequest.builder()
                .messages(List.of(new UserMessage("hello")))
                .context(Map.of("threadId", "t1"))
                .build();
        ModelRequest[] captured = new ModelRequest[1];
        ModelCallHandler handler = request -> {
            captured[0] = request;
            return ModelResponse.of(new AssistantMessage("ok"));
        };

        interceptor.interceptModel(original, handler);

        assertThat(captured[0]).isNotSameAs(original);
        assertThat(captured[0].getSystemMessage()).isNotNull();
        assertThat(captured[0].getSystemMessage().getText()).contains("Plan Mode Active");
        // The prompt lives only on the system message; messages stay untouched.
        assertThat(captured[0].getMessages()).hasSize(1);
    }

    @Test
    void mergesDynamicPromptIntoExistingSystemMessage() {
        PlanModeContext.setEnabled("t1", true);

        ModelRequest original = ModelRequest.builder()
                .systemMessage(new SystemMessage("BASE INSTRUCTION"))
                .messages(List.of(new UserMessage("hello")))
                .context(Map.of("threadId", "t1"))
                .build();
        ModelRequest[] captured = new ModelRequest[1];
        ModelCallHandler handler = request -> {
            captured[0] = request;
            return ModelResponse.of(new AssistantMessage("ok"));
        };

        interceptor.interceptModel(original, handler);

        String text = captured[0].getSystemMessage().getText();
        assertThat(text).startsWith("BASE INSTRUCTION");
        assertThat(text).contains("Plan Mode Active");
    }

    @Test
    void neverInjectsPlanPromptWithoutThreadId() {
        ModelRequest original = ModelRequest.builder()
                .messages(List.of(new UserMessage("hello")))
                .context(Map.of())
                .build();
        ModelRequest[] captured = new ModelRequest[1];
        ModelCallHandler handler = request -> {
            captured[0] = request;
            return ModelResponse.of(new AssistantMessage("ok"));
        };

        interceptor.interceptModel(original, handler);

        assertThat(captured[0].getMessages()).hasSize(1);
        if (captured[0].getSystemMessage() != null) {
            assertThat(captured[0].getSystemMessage().getText())
                    .doesNotContain("Plan Mode Active");
        }
    }
}
