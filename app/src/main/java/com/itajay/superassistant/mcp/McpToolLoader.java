package com.itajay.superassistant.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 以「尽力而为」的方式加载 MCP（以及其他 {@link ToolCallbackProvider}）工具。
 *
 * <p>MCP 是可选能力，而不是启动前提：服务没启动、地址不通、握手或列工具失败，
 * 这里只记一条 WARN 并返回空列表。主 Agent 照常创建，主应用照常启动，其他
 * 本地工具与工作流不受影响。</p>
 *
 * <p>为什么必须先在这里解析：{@code ReactAgent.Builder#build()} 会在构建期调用
 * {@code ToolCallbackProvider#getToolCallbacks()}，异常会直接冒泡导致
 * {@code mainAgent} Bean 创建失败、整个上下文启动失败。先把可用的回调解析出来
 * 并以普通 {@link ToolCallback} 注册，失败路径就只停留在这一层。</p>
 *
 * <p>MCP 客户端配置为 {@code spring.ai.mcp.client.initialized=false}：启动阶段
 * 不主动连接，首次解析工具时才按需初始化（MCP SDK 的 listTools 会懒初始化），
 * 因此「没有服务」不会阻塞启动。</p>
 *
 * <p>探测本身带超时：SDK 的 listTools 在服务不可达时会一直等到客户端的
 * request-timeout（默认 20s），那样启动就白等 20 秒。这里用一个较短的探测超时
 * 兜底，同时不调低 {@code request-timeout}——后者是真实工具调用（如 sendEmail）
 * 的超时，压低会误伤正常业务。</p>
 */
@Component
public class McpToolLoader {

    private static final Logger log = LoggerFactory.getLogger(McpToolLoader.class);

    /**
     * Upper bound for the startup probe of a single provider. A reachable MCP server
     * answers listTools in milliseconds; anything slower is treated as "not there".
     */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);

    private final ObjectProvider<ToolCallbackProvider> toolCallbackProviders;

    public McpToolLoader(ObjectProvider<ToolCallbackProvider> toolCallbackProviders) {
        this.toolCallbackProviders = toolCallbackProviders;
    }

    /**
     * 解析当前可用的外部工具。任何提供者失败都只影响它自己，不会抛出异常。
     *
     * @return 可安全注册给主 Agent 的工具回调；没有可用提供者时返回空列表
     */
    public List<ToolCallback> loadAvailableTools() {
        List<ToolCallback> tools = new ArrayList<>();

        List<ToolCallbackProvider> providers;
        try {
            providers = toolCallbackProviders.orderedStream().toList();
        } catch (Exception e) {
            log.warn("无法初始化外部工具提供者，MCP 等可选工具已跳过（不影响主进程）：{}", e.toString());
            log.debug("外部工具提供者初始化失败详情", e);
            return tools;
        }

        for (ToolCallbackProvider provider : providers) {
            String providerName = provider.getClass().getSimpleName();
            // Never let one slow/dead provider hold the whole startup: a provider that
            // outlives the probe budget is abandoned and its tools simply stay unloaded.
            ExecutorService probe = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "mcp-probe-" + providerName);
                t.setDaemon(true);
                return t;
            });
            Future<ToolCallback[]> pending = probe.submit(provider::getToolCallbacks);
            try {
                ToolCallback[] callbacks = pending.get(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                if (callbacks == null || callbacks.length == 0) {
                    log.info("外部工具提供者 {} 未提供任何工具，跳过", providerName);
                    continue;
                }
                tools.addAll(List.of(callbacks));
                log.info("外部工具提供者 {} 已加载 {} 个工具", providerName, callbacks.length);
            } catch (TimeoutException e) {
                pending.cancel(true);
                log.warn("外部工具提供者 {} 在 {}s 内未就绪，已跳过（不影响主进程）",
                        providerName, PROBE_TIMEOUT.toSeconds());
            } catch (ExecutionException e) {
                // 典型场景：MCP 服务未启动、SSE 连接失败、握手超时。
                // 这是正常降级路径，不升级为错误，更不能让启动失败。
                Throwable cause = e.getCause() == null ? e : e.getCause();
                log.warn("外部工具提供者 {} 不可用，已跳过（不影响主进程）：{}",
                        providerName, cause.toString());
                log.debug("外部工具提供者 {} 不可用详情", providerName, cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pending.cancel(true);
                log.warn("等待外部工具提供者 {} 时被中断，跳过", providerName);
                return tools;
            } finally {
                // Do not shut down with shutdownNow: a cancelled in-flight connection
                // would otherwise be interrupted mid-handshake. Let it drain.
                probe.shutdown();
            }
        }

        return tools;
    }
}
