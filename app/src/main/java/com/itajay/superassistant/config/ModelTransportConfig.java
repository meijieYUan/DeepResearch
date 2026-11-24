package com.itajay.superassistant.config;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 让所有「非流式」模型调用在底层改走流式接口。
 *
 * <p><strong>为什么存在：</strong>本项目曾通过公司网关
 * （{@code llm-gateway.momenta.works}，APISIX 之后）访问模型，该网关
 * **对流式请求放行，但对非流式请求一律返回 413 Request Entity Too Large**——
 * 与实际发送的体积无关：实测主 Agent 的流式请求约 23KB（26 个工具、17KB schema）可以
 * 正常通过，而子 Agent 的非流式请求只有 6–9KB（4 个工具、3–5KB schema）却被拒。
 * 缩小 {@code max-tokens}、裁剪指令与工具描述都无法改变结果，唯一的稳定差异就是
 * {@code "stream": true|false}。当前已改为直连 {@code api.deepseek.com}，该限制不再触发，
 * 但这层适配对任何供应商都等价，默认保留；确认不需要时设
 * {@code agent.model.call-via-stream=false} 关闭。</p>
 *
 * <p>后果曾经非常隐蔽：精读/检索/写作/审查四个子 Agent 都走
 * {@code ChatModel.call()}（非流式），于是它们的**第一次**模型调用就 413。
 * 框架把模型异常吞掉、按「无工具调用的空回答」结束本次运行，工作流拿到的是一段空文本，
 * 各阶段在 130ms 内「成功」结束，最终产出一份没有事实依据的文档——看起来像模型胡说，
 * 实际是请求根本没被接受。</p>
 *
 * <p><strong>做法：</strong>用一个 {@link ChatModel} 装饰器把 {@code call()} 实现为
 * 「订阅 {@code stream()}，再用框架自带的 {@link MessageAggregator} 聚合回一个完整响应」。
 * 这不是模拟：聚合器正是 Spring AI 处理流式工具调用时使用的同一个组件，文本增量与
 * 工具调用参数都会被正确合并。流式路径本身原样透传，主 Agent 的逐字输出不受影响。</p>
 *
 * <p><strong>代价与边界：</strong>非流式调用多了一次流的订阅与聚合，语义等价但延迟略增；
 * 网关修好后可用 {@code agent.model.call-via-stream=false} 关闭（关闭后行为与改动前完全一致）。
 * 只在 Bean 容器里存在 {@code DeepSeekChatModel} 时生效，换供应商不会静默改变行为。</p>
 */
@Configuration
public class ModelTransportConfig {

    /**
     * 聚合整条流后取最后一条完整响应。给足超时：一次调研子 Agent 的回答可能是一篇长文档，
     * 真正的失败（网络、鉴权）会立刻抛出，不需要靠超时兜底。
     */
    private static final Duration AGGREGATE_TIMEOUT = Duration.ofMinutes(15);

    /** 本 Bean 自己的名字，查找委托对象时要跳过，否则会自我依赖。 */
    private static final String SELF_BEAN_NAME = "callViaStreamChatModel";

    @Bean
    @Primary
    public ChatModel callViaStreamChatModel(
            ListableBeanFactory beanFactory,
            @Value("${agent.model.call-via-stream:true}") boolean enabled) {
        if (!enabled) {
            // Returning null leaves the provider's own ChatModel as the only candidate.
            return null;
        }
        // 按类型找供应商注册的那个 ChatModel，跳过自己。不写死 DeepSeekChatModel：
        // 自动配置究竟把哪个实现类注册成 Bean 取决于版本与条件，按接口找才稳。
        for (String name : beanFactory.getBeanNamesForType(ChatModel.class, false, false)) {
            if (SELF_BEAN_NAME.equals(name)) {
                continue;
            }
            return new CallViaStreamChatModel(beanFactory.getBean(name, ChatModel.class));
        }
        return null;
    }

    /** {@code call()} 走流式再聚合；{@code stream()} 原样转发。 */
    static final class CallViaStreamChatModel implements ChatModel {

        private final ChatModel delegate;

        CallViaStreamChatModel(ChatModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            List<ChatResponse> chunks = delegate.stream(prompt).collectList().block(AGGREGATE_TIMEOUT);
            if (chunks == null || chunks.isEmpty()) {
                throw new IllegalStateException("流式聚合没有产生任何响应（模型未返回内容）");
            }
            ChatResponse last = null;
            StringBuilder text = new StringBuilder();
            List<AssistantMessage.ToolCall> calls = new ArrayList<>();
            for (ChatResponse chunk : chunks) {
                if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
                    continue;
                }
                last = chunk;
                AssistantMessage output = chunk.getResult().getOutput();
                if (output.getText() != null && !output.getText().isEmpty()) {
                    text.append(output.getText());
                }
                if (output.getToolCalls() != null) {
                    for (AssistantMessage.ToolCall delta : output.getToolCalls()) {
                        mergeToolCall(calls, delta);
                    }
                }
            }
            // An empty generation would look to the agent loop like "the model answered
            // nothing" — the exact silent failure this adapter exists to avoid.
            if (last == null) {
                throw new IllegalStateException("流式聚合结果缺少 generation");
            }
            AssistantMessage merged = AssistantMessage.builder()
                    .content(text.toString())
                    .toolCalls(calls)
                    .build();
            return new ChatResponse(List.of(new Generation(merged)), last.getMetadata());
        }

        /**
         * 把一次流式工具调用的分片合并成完整调用。
         *
         * <p>供应商的分片约定并不统一：参数增量有的带同一个 id、有的只有第一片带 id，
         * 其余为 null。所以「新调用」的判定是「id 与上一个调用不同」（含 id 为空时视为续片），
         * 而不是「id 非空」——后者会在重复携带 id 的供应商上把一次调用拆成几十个。</p>
         */
        private static void mergeToolCall(List<AssistantMessage.ToolCall> calls,
                                          AssistantMessage.ToolCall delta) {
            if (delta == null) {
                return;
            }
            if (calls.isEmpty()) {
                calls.add(delta);
                return;
            }
            AssistantMessage.ToolCall previous = calls.get(calls.size() - 1);
            boolean continuesPrevious = delta.id() == null || delta.id().isBlank()
                    || delta.id().equals(previous.id());
            if (!continuesPrevious) {
                calls.add(delta);
                return;
            }
            calls.set(calls.size() - 1, new AssistantMessage.ToolCall(
                    previous.id(),
                    previous.type() == null ? delta.type() : previous.type(),
                    delta.name() == null || delta.name().isBlank() ? previous.name() : delta.name(),
                    nullToEmpty(previous.arguments()) + nullToEmpty(delta.arguments())));
        }

        private static String nullToEmpty(String value) {
            return value == null ? "" : value;
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return delegate.stream(prompt);
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return delegate.getDefaultOptions();
        }

        /** Only used when a caller asks the model directly for plain text. */
        @Override
        public String call(String message) {
            AssistantMessage output = call(new Prompt(message)).getResult().getOutput();
            return output.getText();
        }
    }
}
