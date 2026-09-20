package com.itajay.superassistant.config;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.itajay.superassistant.agent.AnalystAgent;
import com.itajay.superassistant.agent.ResearchAgent;
import com.itajay.superassistant.agent.ReviewerAgent;
import com.itajay.superassistant.agent.WriterAgent;
import com.itajay.superassistant.interceptor.ModelCallGuardInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Checks the guard wiring: two beans of the same type, chosen by name.
 *
 * <p>The distinction is easy to get backwards and costly either way — a sub-agent with the
 * fallback would cache "model unavailable" into {@code analysis/}, and the main agent without
 * it would show the user a raw network error. Both are asserted here so a swap fails the build
 * instead of a research run.</p>
 */
class AgentGuardConfigTest {

    /** @Value has defaults, so the context needs no properties to come up. */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AgentGuardConfig.class, ModelCallGuardInterceptor.class);

    private static ModelCallHandler alwaysFailing() {
        return request -> {
            throw new RuntimeException("request failed", new IOException("connection reset"));
        };
    }

    @Test
    void exposesBothGuardsUnderTheNamesTheAgentsAskFor() {
        runner.run(context -> {
            assertThat(context).hasBean("modelCallGuardInterceptor");
            assertThat(context).hasBean("subAgentModelCallGuard");
        });
    }

    /**
     * Two beans of one type are picked by parameter name, which is how the existing
     * {@code paperAnalysisCallLimitHook} already works. Renaming a parameter would silently
     * hand an agent the wrong guard, so the names are asserted rather than assumed.
     */
    @Test
    void everySubAgentAsksForTheRetryOnlyGuardByName() {
        for (Class<?> agent : List.of(ResearchAgent.class, AnalystAgent.class, WriterAgent.class,
                ReviewerAgent.class)) {
            assertThat(Arrays.stream(agent.getConstructors()[0].getParameters())
                    .map(Parameter::getName))
                    .as("%s constructor", agent.getSimpleName())
                    .contains("subAgentModelCallGuard");
        }
    }

    @Test
    void theSubAgentGuardDoesNotInventAnAnswerWhenRetriesRunOut() {
        runner.run(context -> assertThatThrownBy(() -> context.getBean("subAgentModelCallGuard",
                        ModelCallGuardInterceptor.class)
                .interceptModel(ModelRequest.builder().build(), alwaysFailing()))
                .hasMessageContaining("request failed"));
    }

    @Test
    void theMainAgentGuardAnswersInsteadOfFailing() {
        runner.run(context -> {
            AtomicInteger attempts = new AtomicInteger();
            ModelCallHandler handler = request -> {
                attempts.incrementAndGet();
                throw new RuntimeException("request failed", new IOException("connection reset"));
            };

            ModelResponse response = context.getBean("modelCallGuardInterceptor",
                            ModelCallGuardInterceptor.class)
                    .interceptModel(ModelRequest.builder().build(), handler);

            assertThat(response.getMessage().toString()).contains("模型服务当前不可用");
            assertThat(attempts).hasValue(3);
        });
    }
}
