package com.itajay.superassistant.interceptor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import javax.net.ssl.SSLException;
import java.io.EOFException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模型调用兜底拦截器（异常处理兜底措施）：
 * <ol>
 *   <li>对瞬时性错误（DNS 解析失败、超时、限流、连接中断等）进行带指数退避的重试；</li>
 *   <li>重试耗尽后，主 Agent 返回一个友好的兜底回答，避免整个运行因模型异常而崩溃；
 *       子 Agent 则如实抛出——它们会把产出落盘复用（精读结果、文档），替它们编一句话
 *       等于把「模型不可用」缓存下来，交给 workflow 的 catch 上报更安全。</li>
 * </ol>
 *
 * <p>两种行为由 {@link #fallbackOnExhausted} 区分，对应 {@code AgentGuardConfig} 里的两个
 * Bean：主 Agent 用 Spring 注入的这个实例，四个调研子 Agent 用 {@code subAgentModelCallGuard}。</p>
 *
 * <p>两条调用路径都要兜，否则整套机制形同虚设。</p>
 *
 * <p><b>非流式路径</b>：异常在 {@code handler.call} 处同步抛出，循环里直接捕获。</p>
 *
 * <p><b>流式路径（框架默认走这条）</b>：{@code AgentLlmNode} 的 base handler 只把
 * {@code stream().chatResponse()} 构造出的 Flux 交回来就返回了——真正的建连（以及 DNS 解析）
 * 发生在**订阅**时，也就是这个方法的调用栈之外。于是这类错误既绕过了框架自己的
 * try/catch，也绕过了本类同步的 try/catch，一路冒到调用方的 {@code blockLast()}，
 * 以一条原始网络错误结束整个运行。它只能在流上处理，见 {@link #guardStream(Flux)}。</p>
 */
@Component
public class ModelCallGuardInterceptor extends ModelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ModelCallGuardInterceptor.class);

    /** 重试耗尽后的兜底回答。两条路径共用同一句文案，用户看到的行为只取决于网络是否恢复。 */
    private static final String FALLBACK_TEXT =
            "抱歉，模型服务当前不可用或连续调用失败，我暂时无法完成本次回答。请稍后重试，或检查模型 API 配置（密钥、网络、额度）。";

    private final int maxAttempts;
    private final long initialDelayMs;
    private final long maxDelayMs;
    private final double backoffMultiplier;
    private final boolean fallbackOnExhausted;

    // Two constructors exist (the main-agent bean and the sub-agent one, which differ
    // only in fallbackOnExhausted), so Spring must be told which one to use — implicit
    // single-constructor autowiring only applies when there is exactly one.
    @Autowired
    public ModelCallGuardInterceptor(
            @Value("${agent.guard.model-retry.max-attempts:3}") int maxAttempts,
            @Value("${agent.guard.model-retry.initial-delay-ms:500}") long initialDelayMs,
            @Value("${agent.guard.model-retry.max-delay-ms:8000}") long maxDelayMs,
            @Value("${agent.guard.model-retry.backoff-multiplier:2.0}") double backoffMultiplier) {
        this(maxAttempts, initialDelayMs, maxDelayMs, backoffMultiplier, true);
    }

    /**
     * @param fallbackOnExhausted 重试耗尽后是否用一句兜底回答把运行接住。主 Agent 要（用户
     *        看到一句说明，运行继续）；子 Agent 不要（它们的产出会被落盘复用，替它们编一句话
     *        等于把「模型不可用」当成分析结果缓存下来，如实抛出更安全）。
     */
    public ModelCallGuardInterceptor(int maxAttempts, long initialDelayMs, long maxDelayMs,
                                     double backoffMultiplier, boolean fallbackOnExhausted) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initialDelayMs = initialDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.backoffMultiplier = backoffMultiplier;
        this.fallbackOnExhausted = fallbackOnExhausted;
    }

    @Override
    public String getName() {
        return "model_call_guard";
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        ModelResponse response = callWithRetry(request, handler);

        // 流式响应：handler 已经成功返回，真正的失败还在后面等着，把同一套
        // 重试与兜底补到流上。非流式响应（message 是 AssistantMessage）到此为止。
        Object message = response.getMessage();
        if (message instanceof Flux<?> flux) {
            return ModelResponse.of(guardStream(flux.cast(ChatResponse.class), request, handler));
        }
        return response;
    }

    /** 非流式路径：同步抛出的异常在这里重试，耗尽后返回兜底回答。 */
    private ModelResponse callWithRetry(ModelRequest request, ModelCallHandler handler) {
        Exception last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return handler.call(request);
            } catch (Exception e) {
                last = e;
                if (!isTransient(e) || attempt == maxAttempts) {
                    break;
                }
                long delay = backoffFor(attempt);
                log.warn("Model call failed (attempt {}/{}): {}. Retrying in {} ms",
                        attempt, maxAttempts, e.getMessage(), delay);
                sleepQuietly(delay);
            }
        }
        log.error("Model call failed after {} attempt(s): {}", maxAttempts,
                last == null ? "unknown" : last.getMessage(), last);
        if (!fallbackOnExhausted && last != null) {
            // 子 Agent：不替它编一个回答。失败如实冒泡，由 workflow 的 catch 转成
            // 「哪个阶段失败了」上报（见 ResearchWriteReviewWorkflow.invoke）。
            throw last instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException(last.getMessage(), last);
        }
        return ModelResponse.of(new AssistantMessage(FALLBACK_TEXT));
    }

    /**
     * 流式路径：重试与兜底都挂在流上，因为失败只在这里可见。
     *
     * <p>三个约束决定了写法：</p>
     *
     * <ul>
     *   <li><strong>重试要重新发起模型调用，不能重新订阅同一个流。</strong>Spring AI 的流是
     *       针对首次订阅的 reactor 上下文构建的（advisor 链就在那个上下文里），同一个流订阅
     *       第二次会以 {@code IllegalStateException: No StreamAdvisors available to execute}
     *       失败——那样「重试」反而把一个本可恢复的抖动变成了硬失败。所以每次重试都重新走一遍
     *       {@code handler.call}，拿一个全新的流（见 {@link #newStream}）。</li>
     *   <li><strong>只重试「尚未吐出任何分片」的失败。</strong>建连阶段的失败重试是安全的；
     *       但若错误发生在已经产出分片之后（流中途断掉），重试会把同一段文本再发一遍，客户端
     *       就看到重复内容——那种情况直接放行错误。</li>
     *   <li><strong>重试耗尽后给兜底回答，而不是抛出。</strong>与非流式路径一致：运行继续，
     *       用户看到一句说明，而不是一条原始网络错误。</li>
     * </ul>
     *
     * <p>放行中途断流的原因也要说清：此时正文已经有一部分发到客户端了，静默换一句
     * 「模型不可用」会覆盖掉用户已经看到的内容，如实报错反而更可信。</p>
     *
     * @param firstStream 首次尝试的流，框架刚刚交回来的那个
     */
    private Flux<ChatResponse> guardStream(Flux<ChatResponse> firstStream, ModelRequest request,
                                           ModelCallHandler handler) {
        AtomicBoolean firstSubscription = new AtomicBoolean(true);
        AtomicBoolean emitted = new AtomicBoolean();

        // 每次订阅取一个流：首次用框架给的那个，之后（即每次重试）重新发起调用。
        Flux<ChatResponse> attempts = Flux.defer(() -> {
            Flux<ChatResponse> stream = firstSubscription.compareAndSet(true, false)
                    ? firstStream
                    : newStream(request, handler);
            return stream.doOnNext(any -> emitted.set(true));
        });

        Flux<ChatResponse> guarded = attempts;
        if (maxAttempts > 1) {
            guarded = attempts.retryWhen(Retry
                    .backoff(maxAttempts - 1, Duration.ofMillis(Math.max(1, initialDelayMs)))
                    .maxBackoff(Duration.ofMillis(Math.max(1, maxDelayMs)))
                    .jitter(0.5)
                    // 耗尽时放行原始错误，而不是 Reactor 的 RetryExhaustedException——
                    // 后者会把真实原因（DNS 解析失败等）盖成一句「Retries exhausted: 2/2」，
                    // 子 Agent 那条路径上还会原样进到 workflow 的上报文案里。
                    .onRetryExhaustedThrow((spec, signal) -> signal.failure())
                    .filter(error -> !emitted.get() && isTransient(error))
                    .doBeforeRetry(signal -> log.warn(
                            "Streaming model call failed (retry {}/{}): {}",
                            signal.totalRetries() + 1, maxAttempts - 1,
                            signal.failure().getMessage())));
        }

        return guarded.onErrorResume(error -> {
            if (emitted.get() || !fallbackOnExhausted) {
                // 中途断流不兜底（会覆盖用户已看到的内容）；子 Agent 不兜底（产出会被落盘复用）。
                return Flux.error(error);
            }
            log.error("Streaming model call failed after {} attempt(s)", maxAttempts, error);
            return Flux.just(fallbackResponse());
        });
    }

    /**
     * 一次重试所用的新流：再走一遍拦截链，拿一个新的模型调用。
     *
     * <p>同步失败（框架的 base handler 自己 catch 后回一个 AssistantMessage）在这里变成
     * 一个单元素流，让外层的重试与兜底逻辑同样能看见它。</p>
     */
    private Flux<ChatResponse> newStream(ModelRequest request, ModelCallHandler handler) {
        Object message = handler.call(request).getMessage();
        if (message instanceof Flux<?> flux) {
            return flux.cast(ChatResponse.class);
        }
        AssistantMessage text = message instanceof AssistantMessage assistantMessage
                ? assistantMessage
                : new AssistantMessage(String.valueOf(message));
        return Flux.just(new ChatResponse(List.of(new Generation(text)),
                ChatResponseMetadata.builder().usage(new EmptyUsage()).build()));
    }

    /**
     * 兜底回答的流式形态：一个只有文本、没有工具调用的响应。
     *
     * <p>metadata 显式给一个 {@link EmptyUsage}——流式聚合会读
     * {@code getMetadata().getUsage()}，而单参构造出的 {@code ChatResponse} 里 usage 是空的。</p>
     */
    private static ChatResponse fallbackResponse() {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(FALLBACK_TEXT))),
                ChatResponseMetadata.builder().usage(new EmptyUsage()).build());
    }

    private long backoffFor(int attempt) {
        return Math.min(maxDelayMs, (long) (initialDelayMs * Math.pow(backoffMultiplier, attempt - 1)));
    }

    /**
     * 判断异常是否属于可重试的瞬时性错误。
     *
     * <p>先沿 cause 链按类型判断，再看消息关键字。按类型判断是必需的：底层错误会被层层
     * 包装（{@code WebClientRequestException} → reactor 的 {@code ReactiveException} → 真正的
     * {@code UnknownHostException}），只看最外层那句话会漏掉。</p>
     *
     * <p>同一判定也被 {@code AgentGuardConfig} 用于工具重试，所以这里放宽的网络类
     * 异常对工具层同样生效。</p>
     */
    public static boolean isTransient(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof UnknownHostException || t instanceof ConnectException
                    || t instanceof SocketException || t instanceof SocketTimeoutException
                    || t instanceof HttpTimeoutException || t instanceof InterruptedIOException
                    || t instanceof ClosedChannelException || t instanceof EOFException
                    || t instanceof SSLException) {
                return true;
            }
            if (isTransientMessage(t.getMessage())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTransientMessage(String message) {
        if (message == null) {
            return false;
        }
        String m = message.toLowerCase();
        return m.contains("timeout") || m.contains("timed out")
                || m.contains("connection") || m.contains("refused")
                || m.contains("rate limit") || m.contains("too many requests")
                || m.contains("429") || m.contains("500") || m.contains("502")
                || m.contains("503") || m.contains("504") || m.contains("unavailable")
                || m.contains("reset by peer") || m.contains("interrupted")
                // DNS 与建连失败。reactor-netty 的原话是
                // "Failed to resolve 'api.deepseek.com', couldn't setup transport"，
                // 既不叫 timeout 也不叫 connection，早先的关键字表一条都匹配不上，
                // 于是被当成不可重试的致命错误。
                || m.contains("resolve") || m.contains("unknown host")
                || m.contains("no such host") || m.contains("setup transport")
                || m.contains("unreachable") || m.contains("broken pipe")
                || m.contains("handshake") || m.contains("premature");
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
