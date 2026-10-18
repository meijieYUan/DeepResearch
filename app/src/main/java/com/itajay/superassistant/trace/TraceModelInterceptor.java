package com.itajay.superassistant.trace;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Records model invocations (counts and latency, never prompt or answer text).
 *
 * <p>For a streaming response the handler returns the {@link Flux} before the
 * model has finished, so the terminal event is attached to the stream instead of
 * being recorded when the call returns.</p>
 */
@Component
public class TraceModelInterceptor extends ModelInterceptor {

    private final TraceService traceService;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    public TraceModelInterceptor(TraceService traceService) {
        this.traceService = traceService;
    }

    @Override
    public String getName() {
        return "trace_model";
    }

    @Override
    @SuppressWarnings("unchecked")
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        TraceContext.State state = resolveState(request);
        if (state == null) {
            return handler.call(request);
        }
        String detail = "messages=" + (request.getMessages() == null ? 0 : request.getMessages().size())
                + ", tools=" + (request.getTools() == null ? 0 : request.getTools().size());
        long start = System.nanoTime();
        traceService.record(state, TraceEvent.Category.MODEL, "model-call",
                TraceEvent.Status.STARTED, 0, detail, Map.of());

        try {
            ModelResponse response = handler.call(request);
            if (response != null && response.getMessage() instanceof Flux<?> flux) {
                AtomicBoolean finished = new AtomicBoolean();
                AtomicReference<Usage> usage = new AtomicReference<>();
                Flux<ChatResponse> traced = ((Flux<ChatResponse>) flux)
                        .doOnNext(chatResponse -> captureUsage(chatResponse, usage))
                        .doOnComplete(() -> {
                            finish(state, start, finished, TraceEvent.Status.SUCCEEDED,
                                    "stream complete");
                            recordUsage(usage.get());
                        })
                        .doOnError(error -> finish(state, start, finished,
                                TraceEvent.Status.FAILED, TraceSanitizer.error(error, 300)))
                        .doOnCancel(() -> finish(state, start, finished,
                                TraceEvent.Status.INFO, "stream cancelled"));
                return ModelResponse.of(traced);
            }
            traceService.record(state, TraceEvent.Category.MODEL, "model-call",
                    TraceEvent.Status.SUCCEEDED, elapsedMs(start), detail, Map.of());
            recordUsage(response == null ? null : usageOf(response.getChatResponse()));
            return response;
        } catch (RuntimeException e) {
            traceService.record(state, TraceEvent.Category.MODEL, "model-call",
                    TraceEvent.Status.FAILED, elapsedMs(start),
                    TraceSanitizer.error(e, 300), Map.of());
            throw e;
        }
    }

    private void finish(TraceContext.State state, long start, AtomicBoolean finished,
                        TraceEvent.Status status, String detail) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        traceService.record(state, TraceEvent.Category.MODEL, "model-call",
                status, elapsedMs(start), detail, Map.of());
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static Usage usageOf(ChatResponse response) {
        return response == null || response.getMetadata() == null
                ? null : response.getMetadata().getUsage();
    }

    private static void captureUsage(ChatResponse response, AtomicReference<Usage> usage) {
        Usage current = usageOf(response);
        if (current != null) {
            usage.set(current);
        }
    }

    private void recordUsage(Usage usage) {
        if (meterRegistry == null || usage == null) {
            return;
        }
        Integer prompt = usage.getPromptTokens();
        Integer completion = usage.getCompletionTokens();
        if (prompt != null) {
            meterRegistry.counter("agent.model.tokens", "kind", "prompt").increment(prompt);
        }
        if (completion != null) {
            meterRegistry.counter("agent.model.tokens", "kind", "completion").increment(completion);
        }
    }

    private static TraceContext.State resolveState(ModelRequest request) {
        TraceContext.State current = TraceContext.current();
        if (current != null && current.traceId() != null) {
            return current;
        }
        Map<String, Object> context = request.getContext();
        if (context == null) {
            return null;
        }
        String traceId = stringValue(context.get(TraceContext.TRACE_ID));
        if (traceId == null) {
            return null;
        }
        return new TraceContext.State(traceId,
                stringValue(context.get(TraceContext.THREAD_ID)),
                stringValue(context.get(TraceContext.RUN_ID)));
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
