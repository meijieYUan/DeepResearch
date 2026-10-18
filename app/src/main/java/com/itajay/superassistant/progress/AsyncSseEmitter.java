package com.itajay.superassistant.progress;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An {@link SseEmitter} whose writes happen on their own virtual thread.
 *
 * <p>Spring's emitter writes synchronously: a client that is connected but stops
 * reading fills the TCP send buffer, and the thread calling {@code send} — the agent
 * run or the shared heartbeat scheduler — blocks indefinitely. Every write here is
 * queued into a bounded buffer and drained by a per-connection virtual thread; a
 * full queue throws {@link IOException}, which the caller already treats as a dead
 * listener. The run therefore never waits on a slow client.</p>
 *
 * <p>Completion is also queued, so the terminal {@code done} frame is written before
 * the response is completed. A worker that sees no traffic for the stream timeout
 * exits on its own, so removed/timed-out listeners do not leave threads behind.</p>
 */
public class AsyncSseEmitter extends SseEmitter {

    private static final int QUEUE_CAPACITY = 256;

    private record Complete(Throwable error) {
    }

    private final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final long timeoutMs;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread worker;

    public AsyncSseEmitter(long timeoutMs) {
        super(timeoutMs);
        this.timeoutMs = timeoutMs;
        this.worker = Thread.ofVirtual()
                .name("sse-send-" + Long.toHexString(System.nanoTime()))
                .start(this::pump);
    }

    @Override
    public void send(SseEventBuilder builder) throws IOException {
        if (closed.get() || !queue.offer(builder)) {
            throw new IOException("SSE connection closed or its send queue is full");
        }
    }

    @Override
    public void complete() {
        enqueueCompletion(null);
    }

    @Override
    public void completeWithError(Throwable ex) {
        enqueueCompletion(ex);
    }

    private void enqueueCompletion(Throwable error) {
        if (closed.getAndSet(true)) {
            return;
        }
        if (!queue.offer(new Complete(error))) {
            // Queue already saturated: close immediately rather than hang.
            finish(error);
        }
    }

    private void pump() {
        try {
            while (true) {
                Object item = queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
                if (item == null) {
                    finish(null);
                    return;
                }
                if (item instanceof Complete complete) {
                    finish(complete.error());
                    return;
                }
                try {
                    super.send((SseEventBuilder) item);
                } catch (Exception e) {
                    finish(e);
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finish(null);
        }
    }

    private void finish(Throwable error) {
        closed.set(true);
        try {
            if (error != null) {
                super.completeWithError(error);
            } else {
                super.complete();
            }
        } catch (Exception ignored) {
            // The container may already have closed the request.
        }
    }
}
