package com.itajay.superassistant.progress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps one SSE connection per conversation thread so a long-running workflow can
 * report progress while the chat request is still in flight.
 *
 * <p>The chat endpoint stays a blocking POST — this is a <em>second</em>,
 * independent connection. That matters: making the run asynchronous would break
 * {@code PlanContextHolder}'s thread-local and the human-in-the-loop
 * suspend/resume flow, both of which assume one thread for the whole run.</p>
 *
 * <p>Progress is best-effort by design. A workflow must never fail because nobody
 * is listening, so every publish is guarded and a dead connection is simply
 * dropped.</p>
 */
@Component
public class ProgressChannelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProgressChannelRegistry.class);

    /** Long enough for a research run; the connection is closed as soon as the run ends. */
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<String, SseEmitter> channels = new ConcurrentHashMap<>();

    /**
     * Opens a channel for a thread.
     *
     * <p>A second registration for the same thread replaces the first. Concurrent
     * runs on one thread are not supported — the chat endpoint holds one run at a
     * time per thread, so the newer connection is always the live one.</p>
     */
    public SseEmitter register(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId is required");
        }
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);

        // The client going away is normal (navigation, refresh); drop the channel quietly.
        emitter.onCompletion(() -> channels.remove(threadId, emitter));
        emitter.onTimeout(() -> {
            log.debug("SSE channel timed out [thread={}]", threadId);
            channels.remove(threadId, emitter);
        });
        emitter.onError(e -> {
            log.debug("SSE channel errored [thread={}]: {}", threadId, e.getMessage());
            channels.remove(threadId, emitter);
        });

        SseEmitter previous = channels.put(threadId, emitter);
        if (previous != null) {
            log.debug("Replacing an existing SSE channel [thread={}]", threadId);
            previous.complete();
        }
        log.debug("SSE channel opened [thread={}]", threadId);
        return emitter;
    }

    /**
     * Pushes one progress event. A no-op when no client is listening, which is the
     * common case for API-only callers.
     */
    public void publish(String threadId, ProgressEvent event) {
        if (threadId == null || threadId.isBlank() || event == null) {
            return;
        }
        SseEmitter emitter = channels.get(threadId);
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name("progress").data(event));
        } catch (IOException | IllegalStateException e) {
            // Client disconnected mid-send. Nothing to recover: the run continues and
            // the POST response remains the authoritative result.
            log.debug("Dropping SSE channel [thread={}]: {}", threadId, e.getMessage());
            channels.remove(threadId, emitter);
        }
    }

    /**
     * Closes the channel for a thread, if one is open.
     *
     * <p>Sends a terminal {@code done} event first. Closing the stream without one
     * looks to the browser like a dropped connection, and {@code EventSource}
     * responds by reconnecting — which would register a fresh channel for a run
     * that has already finished. An explicit terminal event lets the client close
     * deterministically.</p>
     */
    public void complete(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return;
        }
        SseEmitter emitter = channels.remove(threadId);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name("done").data("{}"));
            } catch (IOException | IllegalStateException e) {
                // Client already gone; closing below is still correct.
                log.debug("Could not send the terminal event [thread={}]: {}", threadId, e.getMessage());
            }
            try {
                emitter.complete();
            } catch (Exception e) {
                log.debug("SSE channel already closed [thread={}]: {}", threadId, e.getMessage());
            }
        }
    }

    /** True when a client is currently listening for this thread. */
    public boolean isConnected(String threadId) {
        return threadId != null && channels.containsKey(threadId);
    }

    /**
     * Comment-only keepalive.
     *
     * <p>Research runs spend minutes inside a single sub-agent call, so without this
     * a proxy or browser can drop an idle connection before the next real event.</p>
     */
    @Scheduled(fixedDelayString = "${agent.progress.heartbeat-ms:15000}")
    public void heartbeat() {
        for (Map.Entry<String, SseEmitter> entry : channels.entrySet()) {
            try {
                entry.getValue().send(SseEmitter.event().comment("keepalive"));
            } catch (IOException | IllegalStateException e) {
                channels.remove(entry.getKey(), entry.getValue());
            }
        }
    }
}
