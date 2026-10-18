package com.itajay.superassistant.app;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.itajay.superassistant.plan.PlanContextHolder;
import com.itajay.superassistant.plan.PlanModeContext;
import com.itajay.superassistant.checkpoint.ThreadStateResetService;
import com.itajay.superassistant.progress.AsyncSseEmitter;
import com.itajay.superassistant.progress.ProgressChannelRegistry;
import com.itajay.superassistant.security.HITLHelper;
import com.itajay.superassistant.security.PendingInterruptionStore;
import com.itajay.superassistant.trace.TraceContext;
import com.itajay.superassistant.trace.TraceEvent;
import com.itajay.superassistant.trace.TraceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import reactor.core.Disposable;

/**
 * Runs one agent turn and streams everything it produces to the client.
 *
 * <p>The POST endpoints return this service's emitter directly, so the response body
 * <em>is</em> the run: progress events (fanned in from {@link ProgressChannelRegistry},
 * where workflow tools publish), the model's incremental text, one terminal event,
 * and {@code done}. Terminal states:</p>
 *
 * <ul>
 *   <li>{@code answer} — the run finished; text comes from {@code state.value("output")},
 *       the same key {@code invokeAndGetOutput} used to read;</li>
 *   <li>{@code interruption} — a high-risk tool is waiting for approval; the config and
 *       metadata go into {@link PendingInterruptionStore} exactly as the blocking flow
 *       did, so {@code POST /approve} resumes it;</li>
 *   <li>{@code error} — the run failed; the message carries what the exception said.</li>
 * </ul>
 *
 * <p>Every run ends with {@code done}, then the emitter completes.</p>
 *
 * <p>Two deliberate decisions:</p>
 * <ul>
 *   <li><strong>A client disconnect never cancels the run.</strong> Sends are guarded;
     *    a dead emitter only stops further forwarding. Persistence, checkpoints and the
 *       human-approval flow all complete server-side regardless.</li>
 *   <li><strong>One run per thread at a time.</strong> A second request for the same
 *       thread gets an immediate {@code error}+{@code done} instead of two agents
 *       interleaving their tool calls on one conversation.</li>
 * </ul>
 */
@Service
public class ChatStreamingService {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamingService.class);

    /**
     * Stream lifetime cap. A research workflow can run for a long time; when this
     * fires the HTTP stream closes but the run itself continues and the answer still
     * lands in the persisted history. Configurable via {@code agent.stream.timeout-minutes}
     * (the field initializer is the default for direct instantiation in tests).
     */
    @Value("${agent.stream.timeout-minutes:60}")
    private long timeoutMinutes = 60;

    /**
     * Hard cap on concurrently executing runs. Each run occupies its thread for its
     * whole (potentially hour-long) lifetime, so an unbounded pool let a burst of
     * distinct threadIds exhaust memory/threads without limit. Overflow is refused
     * with a clear message instead of queueing invisibly.
     */
    private static final int MAX_CONCURRENT_RUNS = 16;

    private final AgentRunner agentRunner;
    private final ProgressChannelRegistry progressChannels;
    private final PendingInterruptionStore pendingInterruptionStore;
    /** Null in unit tests that construct the service without a Spring context. */
    private final TraceService traceService;
    /** Null in unit tests; resets the graph checkpoint after a cancelled run. */
    private final ThreadStateResetService threadStateResetService;

    private final ExecutorService runner = new ThreadPoolExecutor(
            0, MAX_CONCURRENT_RUNS, 60L, TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            task -> {
                Thread thread = new Thread(task, "chat-stream");
                thread.setDaemon(true);
                return thread;
            });

    private final ConcurrentHashMap<String, Boolean> running = new ConcurrentHashMap<>();
    /** Live subscription per thread, so a user cancel can actually stop the run. */
    private final ConcurrentHashMap<String, RunHandle> activeRuns = new ConcurrentHashMap<>();

    private static final class RunHandle {
        private volatile Disposable subscription;
        private volatile Thread thread;
        private volatile CompletableFuture<Void> completion;
        private final AtomicBoolean cancelled = new AtomicBoolean();
    }

    /** One run's stream: same shape as {@code Agent.stream(String, RunnableConfig)}. */
    @FunctionalInterface
    interface AgentRunner {
        Flux<NodeOutput> stream(String input, RunnableConfig config) throws Exception;
    }

    // Two constructors exist (the package-private one is a test seam), so Spring must
    // be told which one to use — implicit single-constructor autowiring only applies
    // when there is exactly one.
    @Autowired
    public ChatStreamingService(ReactAgent mainAgent,
                                ProgressChannelRegistry progressChannels,
                                PendingInterruptionStore pendingInterruptionStore,
                                TraceService traceService,
                                ThreadStateResetService threadStateResetService) {
        this(mainAgent::stream, progressChannels, pendingInterruptionStore, traceService,
                threadStateResetService);
    }

    ChatStreamingService(AgentRunner agentRunner,
                         ProgressChannelRegistry progressChannels,
                         PendingInterruptionStore pendingInterruptionStore) {
        this(agentRunner, progressChannels, pendingInterruptionStore, null, null);
    }

    ChatStreamingService(AgentRunner agentRunner,
                         ProgressChannelRegistry progressChannels,
                         PendingInterruptionStore pendingInterruptionStore,
                         TraceService traceService) {
        this(agentRunner, progressChannels, pendingInterruptionStore, traceService, null);
    }

    ChatStreamingService(AgentRunner agentRunner,
                         ProgressChannelRegistry progressChannels,
                         PendingInterruptionStore pendingInterruptionStore,
                         TraceService traceService,
                         ThreadStateResetService threadStateResetService) {
        this.agentRunner = agentRunner;
        this.progressChannels = progressChannels;
        this.pendingInterruptionStore = pendingInterruptionStore;
        this.traceService = traceService;
        this.threadStateResetService = threadStateResetService;
    }

    /**
     * Starts the run and returns the emitter the controller should return as the
     * response body. The call returns immediately; the run proceeds on this service's
     * thread and everything it produces is forwarded to the emitter.
     */
    public SseEmitter start(String threadId, String input, RunnableConfig config, boolean planEnabled) {
        return start(threadId, input, config, planEnabled, null);
    }

    public SseEmitter start(String threadId, String input, RunnableConfig config,
                            boolean planEnabled, Runnable onAccepted) {
        return start(threadId, input, config, planEnabled, onAccepted,
                TraceContext.currentTraceId());
    }

    /**
     * Variant with an {@code onAccepted} callback for side effects (persisting the
     * user message, recording the plan-mode choice) that must happen only when the
     * request is actually accepted.
     *
     * <p>The callback runs inside the submitted task, before the agent starts. It
     * used to run before {@code runner.submit}, so when the pool was saturated the
     * request was rejected <em>after</em> the user message had been persisted — a
     * message that no run would ever answer. If the callback fails, the stream
     * carries an {@code error} and {@code done} instead of leaving a half-started
     * run behind.</p>
     */
    public SseEmitter start(String threadId, String input, RunnableConfig config,
                            boolean planEnabled, Runnable onAccepted, String traceId) {
        SseEmitter emitter = new AsyncSseEmitter(timeoutMs());

        if (threadId == null || threadId.isBlank()) {
            return rejected(threadId, "threadId 不能为空");
        }
        String runTraceId = TraceContext.normalize(traceId);
        String runId = UUID.randomUUID().toString();
        if (config != null) {
            config.context().put(TraceContext.TRACE_ID, runTraceId);
            config.context().put(TraceContext.RUN_ID, runId);
            // ModelRequest.context is built from RunnableConfig.metadata (see
            // AgentLlmNode), so the model interceptor needs the ids there too.
            try {
                config.metadata().ifPresent(metadata -> {
                    metadata.put(TraceContext.TRACE_ID, runTraceId);
                    metadata.put(TraceContext.RUN_ID, runId);
                });
            } catch (UnsupportedOperationException ignored) {
                // An immutable metadata map still leaves the ThreadLocal fallback.
            }
        }
        if (running.putIfAbsent(threadId, Boolean.TRUE) != null) {
            return rejected(threadId, "该会话已有任务在执行中，请等待其完成后再发送新消息");
        }

        // Progress events from workflow tools reach the response stream through the
        // registry's fan-out; the emitter's own callbacks detach it when the client
        // disconnects, which must not stop the run (see run()).
        try {
            if (!progressChannels.attach(threadId, emitter)) {
                log.warn("Progress listener limit reached; run continues without progress [thread={}]",
                        threadId);
            }
        } catch (RuntimeException e) {
            // Without this, the slot acquired above would leak forever and the
            // thread would keep reporting "already running".
            running.remove(threadId);
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // best effort
            }
            log.warn("Could not attach progress channel [thread={}]", threadId, e);
            return rejected(threadId, "无法建立进度通道：" + e.getMessage());
        }

        try {
            runner.submit(() -> {
                try (TraceContext.Scope traceScope = TraceContext.open(runTraceId, threadId, runId)) {
                    if (traceService != null) {
                        traceService.beginRun(runTraceId, threadId, runId,
                                "planEnabled=" + planEnabled);
                    }
                    if (onAccepted != null) {
                        try {
                            onAccepted.run();
                        } catch (RuntimeException e) {
                            log.error("Run setup failed after acceptance [thread={}]", threadId, e);
                            if (traceService != null) {
                                traceService.endRun(TraceEvent.Status.FAILED,
                                        "run setup failed: " + e.getMessage());
                            }
                            pendingInterruptionStore.remove(threadId);
                            send(emitter, "error", Map.of("message", e.getMessage() == null
                                    ? "Run setup failed" : e.getMessage()));
                            send(emitter, "done", Map.of());
                            try {
                                emitter.complete();
                            } catch (Exception ignored) {
                                // best effort
                            }
                            running.remove(threadId);
                            return;
                        }
                    }
                    run(threadId, input, config, planEnabled, emitter);
                }
            });
        } catch (RejectedExecutionException e) {
            running.remove(threadId);
            try {
                emitter.complete(); // fires onCompletion → detaches from the registry
            } catch (Exception ignored) {
                // best effort
            }
            log.warn("Run refused, pool saturated ({} concurrent runs) [thread={}]",
                    MAX_CONCURRENT_RUNS, threadId);
            return rejected(threadId, "服务端并发任务已达上限（" + MAX_CONCURRENT_RUNS + "），请稍后再试");
        }
        return emitter;
    }

    private long timeoutMs() {
        return timeoutMinutes * 60_000L;
    }

    /**
     * A stream that carries only an error and a close: for a request that could not
     * even start a run (no pending interruption to approve, store failure, a run
     * already in flight). The HTTP status stays 200 — the status line cannot carry
     * per-run meaning once the body is an event stream.
     */
    public SseEmitter rejected(String threadId, String message) {
        SseEmitter emitter = new AsyncSseEmitter(timeoutMs());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", message == null ? "Unknown error" : message);
        String traceId = TraceContext.currentTraceId();
        if (traceId != null) {
            payload.put(TraceContext.TRACE_ID, traceId);
        }
        send(emitter, "error", payload);
        send(emitter, "done", Map.of());
        emitter.complete();
        return emitter;
    }

    /**
     * Cancels the run for a thread, if one is active.
     *
     * <p>Disposing the Reactor subscription aborts an in-flight model stream; the
     * thread interrupt additionally reaches blocking work (HTTP tool calls, terminal
     * processes, file IO). The run still goes through its normal terminal cleanup,
     * so the thread slot and pending approval state are released.</p>
     *
     * @return true when an active run was signalled; false when nothing was running
     */
    public boolean cancel(String threadId) {
        RunHandle handle = threadId == null ? null : activeRuns.get(threadId);
        if (handle == null) {
            return false;
        }
        handle.cancelled.set(true);
        Disposable subscription = handle.subscription;
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
        CompletableFuture<Void> completion = handle.completion;
        if (completion != null) {
            completion.completeExceptionally(new CancellationException("cancelled by user"));
        }
        Thread thread = handle.thread;
        if (thread != null) {
            thread.interrupt();
        }
        log.info("Run cancellation requested [thread={}]", threadId);
        return true;
    }

    private void run(String threadId, String input, RunnableConfig config,
                     boolean planEnabled, SseEmitter emitter) {
        AtomicBoolean open = new AtomicBoolean(true);
        AtomicReference<NodeOutput> last = new AtomicReference<>();
        AtomicReference<InterruptionMetadata> interruption = new AtomicReference<>();
        TraceEvent.Status terminalStatus = TraceEvent.Status.FAILED;
        String terminalDetail = "run ended without a terminal event";
        boolean resetState = false;
        RunHandle handle = new RunHandle();
        handle.thread = Thread.currentThread();
        CompletableFuture<Void> completion = new CompletableFuture<>();
        handle.completion = completion;
        activeRuns.put(threadId, handle);

        // Fallback for the one piece of state that used to assume a single thread.
        // The primary source is now ModelRequest.getContext() (see
        // PlanModeToolInterceptor); this covers anything that still reads the holder.
        PlanContextHolder.setThreadId(threadId);
        try {
            Disposable subscription = agentRunner.stream(input, config)
                    .doOnNext(output -> {
                        last.set(output);
                        if (output instanceof InterruptionMetadata metadata) {
                            interruption.set(metadata);
                        }
                        if (output instanceof StreamingOutput<?> streaming
                                && streaming.getOutputType() == OutputType.AGENT_MODEL_STREAMING) {
                            String chunk = streaming.chunk();
                            if (chunk != null && !chunk.isEmpty()) {
                                sendIfOpen(open, emitter, "delta", Map.of("text", chunk));
                            }
                        }
                    })
                    .subscribe(ignored -> { }, completion::completeExceptionally,
                            () -> completion.complete(null));
            handle.subscription = subscription;

            try {
                completion.get();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof CancellationException cancellation) {
                    throw cancellation;
                }
                if (cause instanceof Exception exception) {
                    throw exception;
                }
                throw new RuntimeException(cause);
            }
            if (handle.cancelled.get()) {
                throw new CancellationException("cancelled by user");
            }

            InterruptionMetadata metadata = interruption.get();
            if (metadata != null) {
                terminalStatus = TraceEvent.Status.INFO;
                terminalDetail = "interruption: waiting for approval";
                pendingInterruptionStore.put(threadId, config, metadata, input);
                sendIfOpen(open, emitter, "interruption",
                        interruptionPayload(threadId, metadata, planEnabled));
            } else if (last.get() == null) {
                // An empty Flux used to leave the previous pending interruption in
                // the store, locking the thread for the full TTL.
                terminalStatus = TraceEvent.Status.FAILED;
                terminalDetail = "agent returned empty result";
                pendingInterruptionStore.remove(threadId);
                sendIfOpen(open, emitter, "error", Map.of("message", "Agent returned empty result"));
            } else {
                // The output key holds an AssistantMessage, not prose — normalizing
                // here keeps answer.text a plain string so the client never has to
                // dig a message object out of the payload (the old extractText hack).
                Object answer = last.get().state().value("output").orElse(null);
                if (answer instanceof org.springframework.ai.chat.messages.AssistantMessage message) {
                    answer = message.getText();
                }
                if (answer == null) {
                    answer = last.get().toString();
                }
                terminalStatus = TraceEvent.Status.SUCCEEDED;
                terminalDetail = "answer " + String.valueOf(answer).length() + " chars";
                pendingInterruptionStore.remove(threadId);
                sendIfOpen(open, emitter, "answer", answerPayload(threadId, answer, planEnabled));
            }
        } catch (CancellationException e) {
            // Clear the flag: this thread returns to the pool and the next run must
            // not start with an interrupt set (it would fail every model call).
            Thread.interrupted();
            resetState = true;
            log.info("Chat stream cancelled [thread={}]", threadId);
            terminalStatus = TraceEvent.Status.INFO;
            terminalDetail = "cancelled by user";
            pendingInterruptionStore.remove(threadId);
            sendIfOpen(open, emitter, "error",
                    Map.of("message", "运行已取消；该会话的模型上下文已重置，可直接发送新消息。"));
        } catch (InterruptedException e) {
            // Handled here rather than propagated: the pooled worker is reused.
            Thread.interrupted();
            resetState = true;
            log.info("Chat stream interrupted [thread={}]", threadId);
            terminalStatus = TraceEvent.Status.INFO;
            terminalDetail = "cancelled by interrupt";
            pendingInterruptionStore.remove(threadId);
            sendIfOpen(open, emitter, "error",
                    Map.of("message", "运行已取消；该会话的模型上下文已重置，可直接发送新消息。"));
        } catch (Exception e) {
            log.error("Chat stream failed [thread={}]", threadId, e);
            terminalStatus = TraceEvent.Status.FAILED;
            terminalDetail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            pendingInterruptionStore.remove(threadId);
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            sendIfOpen(open, emitter, "error", Map.of("message", message));
        } finally {
            activeRuns.remove(threadId, handle);
            if (resetState && threadStateResetService != null) {
                threadStateResetService.reset(threadId);
            }
            Disposable subscription = handle.subscription;
            if (subscription != null && !subscription.isDisposed()) {
                subscription.dispose();
            }
            if (traceService != null) {
                traceService.endRun(terminalStatus, terminalDetail);
            }
            PlanContextHolder.clear();
            sendIfOpen(open, emitter, "done", Map.of());
            open.set(false);
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // the client may already be gone; the run is finished either way
            }
            running.remove(threadId);
            log.info("Chat stream finished [thread={}]", threadId);
        }
    }

    private Map<String, Object> answerPayload(String threadId, Object answer, boolean planEnabled) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("threadId", threadId);
        payload.put("text", answer);
        payload.put("planEnabled", planEnabled);
        payload.put("planActive", PlanModeContext.isActive(threadId));
        String traceId = TraceContext.currentTraceId();
        if (traceId != null) {
            payload.put(TraceContext.TRACE_ID, traceId);
        }
        return payload;
    }

    private Map<String, Object> interruptionPayload(String threadId, InterruptionMetadata metadata,
                                                    boolean planEnabled) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("threadId", threadId);
        payload.put("message", "High-risk operations require approval");
        payload.put("pendingApprovals", HITLHelper.getPendingApprovals(metadata));
        payload.put("planEnabled", planEnabled);
        payload.put("planActive", PlanModeContext.isActive(threadId));
        String traceId = TraceContext.currentTraceId();
        if (traceId != null) {
            payload.put(TraceContext.TRACE_ID, traceId);
        }
        return payload;
    }

    private void sendIfOpen(AtomicBoolean open, SseEmitter emitter, String event, Object payload) {
        if (open.get() && !send(emitter, event, payload)) {
            // First failed send means the client is gone; stop attempting further
            // sends but let the run finish (persistence and checkpoints continue).
            open.set(false);
        }
    }

    /** Returns false when the send failed, i.e. the connection is no longer usable. */
    protected boolean send(SseEmitter emitter, String event, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(event).data(payload));
            return true;
        } catch (Exception e) {
            // Client gone or connection broken. Never fatal: the run continues and
            // only the forwarding stops.
            log.debug("SSE send failed; stopping further forwarding: {}", e.getMessage());
            return false;
        }
    }
}
