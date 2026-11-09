package com.itajay.superassistant.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.client.common.autoconfigure.McpClientAutoConfiguration;
import org.springframework.ai.mcp.client.common.autoconfigure.McpToolCallbackAutoConfiguration;
import org.springframework.ai.mcp.client.webflux.autoconfigure.SseWebFluxTransportAutoConfiguration;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.webclient.autoconfigure.WebClientAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the MCP client no longer connects during startup: with
 * {@code spring.ai.mcp.client.initialized=false} the context comes up even though the
 * configured SSE endpoint is unreachable, and tool resolution degrades to an empty list.
 *
 * <p>With the framework default ({@code initialized=true}) the client calls
 * {@code initialize()} while creating the {@code McpSyncClient} beans, so an absent
 * server fails context startup. That is the hard dependency this change removes.</p>
 */
class McpOptionalStartupTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class,
                    WebClientAutoConfiguration.class,
                    McpClientAutoConfiguration.class,
                    SseWebFluxTransportAutoConfiguration.class,
                    McpToolCallbackAutoConfiguration.class))
            .withPropertyValues(
                    "spring.ai.mcp.client.enabled=true",
                    "spring.ai.mcp.client.initialized=false",
                    "spring.ai.mcp.client.type=SYNC",
                    "spring.ai.mcp.client.toolcallback.enabled=true",
                    "spring.ai.mcp.client.request-timeout=1s",
                    // Port 1 is never listening in the test environment.
                    "spring.ai.mcp.client.sse.connections.email-server.url=http://localhost:1",
                    "spring.ai.mcp.client.sse.connections.email-server.sse-endpoint=/sse");

    @Test
    void contextStartsAndToolsDegradeWhenTheMcpServerIsAbsent() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            // The MCP client and its tool-callback provider are still wired; only the
            // eager connection is gone. That is what makes the degradation meaningful.
            assertThat(context).hasSingleBean(ToolCallbackProvider.class);

            McpToolLoader loader = new McpToolLoader(
                    context.getBeanProvider(ToolCallbackProvider.class));
            assertThat(loader.loadAvailableTools()).isEmpty();
        });
    }
}
