package com.itajay.superassistant.progress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

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

    /** Long enough for a research run; the connection is closed as soon as the run ends. */
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> channels = new ConcurrentHashMap<>();

    /**
     * Opens a progress-only connection for a thread (the debug endpoint).
     *
     * <p>The POST response stream does not come from here — it is created by the
     * request itself and attached via {@link #attach}. This method exists so the
     * run's progress can also be watched from outside the chat stream.</p>
     */
    public SseEmitter register(String threadId) {
        SseEmitter emitter = newEmitter();
        attach(threadId, emitter);
        log.debug("Progress stream opened [thread={}]", threadId);
        return emitter;
    }

    /**
     * Attaches an externally-owned emitter (the POST response stream) to a thread's
     * progress fan-out. Removal from the fan-out is wired to the emitter's own
     * lifecycle, so a completed connection stops receiving events without anyone
     * having to remember to detach it.
     */
    public void attach(String threadId, SseEmitter emitter) {
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId is required");
        }
        CopyOnWriteArrayList<SseEmitter> list = listFor(threadId);
        list.add(emitter);
        Runnable remove = () -> {
            list.remove(emitter);
            channels.remove(threadId, list);
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
    }

    /**
     * Pushes one progress event to every listener of the thread. A no-op when no one
     * is listening, which is the common case for API-only callers.
     */
    public void publish(String threadId, ProgressEvent event) {
        if (threadId == null || threadId.isBlank() || event == null) {
            return;
        }
        CopyOnWriteArrayList<SseEmitter> list = channels.get(threadId);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("progress").data(event));
            } catch (IOException | IllegalStateException e) {
                // Client disconnected mid-send. Drop this listener; the run and the
                // other listeners carry on.
                log.debug("Dropping SSE channel [thread={}]: {}", threadId, e.getMessage());
                list.remove(emitter);
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
                    entry.getValue().remove(emitter);
                }
            }
        }
    }

    private SseEmitter newEmitter() {
        return new SseEmitter(TIMEOUT_MS);
    }

    private CopyOnWriteArrayList<SseEmitter> listFor(String threadId) {
        return channels.computeIfAbsent(threadId, k -> new CopyOnWriteArrayList<>());
    }
}
