package com.itajay.superassistant.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MCP is a soft dependency: a missing or unreachable provider must be skipped,
 * never propagated to the main agent's build step.
 */
class McpToolLoaderTest {

    @Test
    void loadsToolsFromAnAvailableProvider() {
        ToolCallback callback = mock(ToolCallback.class);
        ToolCallbackProvider provider = ToolCallbackProvider.from(callback);

        assertThat(loaderFor(provider).loadAvailableTools()).containsExactly(callback);
    }

    @Test
    void unavailableProviderIsSkippedWithoutFailing() {
        // Simulates an MCP server that is down: getToolCallbacks() throws while connecting.
        ToolCallbackProvider unreachable = () -> {
            throw new IllegalStateException("Connection refused: localhost:8081");
        };

        assertThat(loaderFor(unreachable).loadAvailableTools()).isEmpty();
    }

    @Test
    void healthyProvidersStillLoadWhenAnotherProviderIsDown() {
        ToolCallback callback = mock(ToolCallback.class);
        ToolCallbackProvider unreachable = () -> {
            throw new IllegalStateException("Connection refused");
        };
        ToolCallbackProvider healthy = ToolCallbackProvider.from(callback);

        assertThat(loaderFor(unreachable, healthy).loadAvailableTools()).containsExactly(callback);
    }

    @Test
    void noProviderMeansNoTools() {
        assertThat(loaderFor().loadAvailableTools()).isEmpty();
    }

    @SafeVarargs
    private static McpToolLoader loaderFor(ToolCallbackProvider... providers) {
        @SuppressWarnings("unchecked")
        ObjectProvider<ToolCallbackProvider> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(Stream.of(providers));
        return new McpToolLoader(provider);
    }
}
