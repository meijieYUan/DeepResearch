package com.itajay.superassistant.progress;

import com.itajay.superassistant.trace.TraceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fans progress events out to every connection listening for a conversation thread.
 *
 * <p>Since the chat endpoints stream their whole response, a thread can have more
 * than one listener at a time: the {@code POST /chat/{threadId}} response stream
 * attaches itself here so progress lands in the same stream as the answer, and the
 * debug-only {@code GET /chat/{threadId}/stream} attaches another. Publishing is
 * therefore multicast: every {@code publish} call reaches all of them, and one dead
 * connection never affects the run or the other listeners.</p>
 *
 * <p>Progress is best-effort by design. A run must never fail because nobody is
 * listening, so every send is guarded and a failed send just drops that listener.</p>
 */
@Component
public class ProgressChannelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProgressChannelRegistry.class);

    /**
     * Idle-connection cap; the connection is closed as soon as the run ends anyway.
     * Must cover the longest expected workflow — configurable, and defaulted to match
     * {@code ChatStreamingService}'s stream timeout (the initializer is the fallback
     * for direct instantiation in tests).
     */
    @Value("${agent.stream.timeout-minutes:60}")
    private long timeoutMinutes = 60;

    /** Bounds the number of SseEmitters (each holds a servlet async request). */
    private static final int MAX_LISTENERS_PER_THREAD = 4;
    private static final int MAX_TOTAL_LISTENERS = 200;

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> channels = new ConcurrentHashMap<>();
    private final AtomicInteger totalListeners = new AtomicInteger();

    /**
     * Opens a progress-only connection for a thread (the debug endpoint).
     *
     * <p>The POST response stream does not come from here — it is created by the
     * request itself and attached via {@link #attach}. This method exists so the
     * run's progress can also be watched from outside the chat stream.</p>
     */
    public SseEmitter register(String threadId) {
        SseEmitter emitter = newEmitter();
        if (!attach(threadId, emitter)) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // already closed
            }
        }
        log.debug("Progress stream opened [thread={}]", threadId);
        return emitter;
    }

    /**
     * Attaches an externally-owned emitter (the POST response stream) to a thread's
     * progress fan-out. Removal from the fan-out is wired to the emitter's own
     * lifecycle, so a completed connection stops receiving events without anyone
     * having to remember to detach it.
     */
    public boolean attach(String threadId, SseEmitter emitter) {
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId is required");
        }
        CopyOnWriteArrayList<SseEmitter> existing = channels.get(threadId);
        if ((existing != null && existing.size() >= MAX_LISTENERS_PER_THREAD)
                || totalListeners.get() >= MAX_TOTAL_LISTENERS) {
            log.warn("SSE listener limit reached [thread={}, perThread={}, total={}]",
                    threadId, existing == null ? 0 : existing.size(), totalListeners.get());
            return false;
        }
        AtomicBoolean detached = new AtomicBoolean();
        Runnable remove = () -> {
            detached.set(true);
            removeEmitter(threadId, emitter);
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(() -> {
            log.debug("SSE channel timed out [thread={}]", threadId);
            remove.run();
        });
        emitter.onError(e -> {
            log.debug("SSE channel errored [thread={}]: {}", threadId, e.getMessage());
            remove.run();
        });
        // Wire callbacks before exposing the emitter to publishers. A callback can
        // run during registration or addition; remember it and remove again after
        // addition so a finished connection is never left behind in the fan-out.
        AtomicBoolean added = new AtomicBoolean();
        channels.compute(threadId, (key, list) -> {
            if (detached.get()) {
                return list;
            }
            if (list == null) {
                list = new CopyOnWriteArrayList<>();
            }
            list.add(emitter);
            added.set(true);
            return list;
        });
        if (added.get()) {
            totalListeners.incrementAndGet();
        }
        if (detached.get()) {
            removeEmitter(threadId, emitter);
        }
        return true;
    }

    /**
     * Pushes one progress event to every listener of the thread. A no-op when no one
     * is listening, which is the common case for API-only callers.
     */
    public void publish(String threadId, ProgressEvent event) {
        sendAll(threadId, "progress", event);
    }

    /**
     * Pushes one execution-trace event to the thread's listeners. Same transport as
     * progress, different frame name; the frontend renders it as the live timeline
     * and the trace is also persisted by {@code TraceService}.
     */
    public void publishTrace(String threadId, TraceEvent event) {
        sendAll(threadId, "trace", event);
    }

    private void sendAll(String threadId, String frame, Object payload) {
        if (threadId == null || threadId.isBlank() || payload == null) {
            return;
        }
        CopyOnWriteArrayList<SseEmitter> list = channels.get(threadId);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(frame).data(payload));
            } catch (IOException | IllegalStateException e) {
                // Client disconnected mid-send. Drop this listener; the run and the
                // other listeners carry on.
                log.debug("Dropping SSE channel [thread={}]: {}", threadId, e.getMessage());
                removeEmitter(threadId, emitter);
            }
        }
    }

    /**
     * Comment-only keepalive for every attached connection.
     *
     * <p>Research runs spend minutes inside a single sub-agent call, so without this
     * a proxy or browser can drop an idle connection before the next real event.</p>
     */
    @Scheduled(fixedDelayString = "${agent.progress.heartbeat-ms:15000}")
    public void heartbeat() {
        for (Map.Entry<String, CopyOnWriteArrayList<SseEmitter>> entry : channels.entrySet()) {
            for (SseEmitter emitter : entry.getValue()) {
                try {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                } catch (IOException | IllegalStateException e) {
                    removeEmitter(entry.getKey(), emitter);
                }
            }
        }
    }

    private SseEmitter newEmitter() {
        return new AsyncSseEmitter(timeoutMinutes * 60_000L);
    }

    private void removeEmitter(String threadId, SseEmitter emitter) {
        AtomicBoolean removed = new AtomicBoolean();
        channels.computeIfPresent(threadId, (key, list) -> {
            if (list.remove(emitter)) {
                removed.set(true);
            }
            // Keep the mapping while other subscribers remain. This runs atomically
            // with attach so a concurrent subscriber cannot join an orphaned list.
            return list.isEmpty() ? null : list;
        });
        if (removed.get()) {
            totalListeners.decrementAndGet();
        }
    }
}
