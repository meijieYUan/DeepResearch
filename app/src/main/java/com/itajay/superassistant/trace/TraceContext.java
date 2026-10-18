package com.itajay.superassistant.trace;

import org.slf4j.MDC;

import java.security.SecureRandom;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Per-run trace identity: a traceId shared by every log line and trace event of a
 * request, plus the thread/run identifiers used to correlate concurrent work.
 *
 * <p>The state lives in a {@link ThreadLocal} for code that cannot carry a context
 * explicitly (interceptors, tools), and in SLF4J's {@link MDC} so the logging
 * pattern can print it. Async work must be wrapped with {@link #wrap(Runnable)} —
 * platform threads do not inherit thread-locals or MDC.</p>
 */
public final class TraceContext {

    public static final String TRACE_ID = "traceId";
    public static final String THREAD_ID = "threadId";
    public static final String RUN_ID = "runId";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private TraceContext() {
    }

    public record State(String traceId, String threadId, String runId) {
    }

    public static State current() {
        return CURRENT.get();
    }

    public static String currentTraceId() {
        State state = CURRENT.get();
        return state == null ? null : state.traceId();
    }

    /** Generates a trace id safe for logs, headers and file names. */
    public static String newTraceId() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder("tr-");
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** Accepts a caller-provided id only when it is short and log/path safe. */
    public static String normalize(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return newTraceId();
        }
        String trimmed = traceId.trim();
        return trimmed.matches("[A-Za-z0-9._-]{8,64}") ? trimmed : newTraceId();
    }

    /** Opens a scope; closing restores the previous thread state and MDC values. */
    public static Scope open(String traceId, String threadId, String runId) {
        State previous = CURRENT.get();
        State next = new State(normalize(traceId), threadId, runId);
        CURRENT.set(next);
        putMdc(next);
        return new Scope(previous);
    }

    public static Scope open(State state) {
        return open(state.traceId(), state.threadId(), state.runId());
    }

    public static Runnable wrapRunnable(Runnable task) {
        State captured = CURRENT.get();
        return () -> {
            try (Scope ignored = captured == null ? null : open(captured)) {
                task.run();
            }
        };
    }

    public static <T> Callable<T> wrapCallable(Callable<T> task) {
        State captured = CURRENT.get();
        return () -> {
            try (Scope ignored = captured == null ? null : open(captured)) {
                return task.call();
            }
        };
    }

    public static <T> Supplier<T> wrapSupplier(Supplier<T> task) {
        State captured = CURRENT.get();
        return () -> {
            try (Scope ignored = captured == null ? null : open(captured)) {
                return task.get();
            }
        };
    }

    private static void putMdc(State state) {
        MDC.put(TRACE_ID, state.traceId());
        putOrRemove(THREAD_ID, state.threadId());
        putOrRemove(RUN_ID, state.runId());
    }

    private static void putOrRemove(String key, String value) {
        if (value == null || value.isBlank()) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    private static void restore(State previous) {
        if (previous == null) {
            CURRENT.remove();
            MDC.remove(TRACE_ID);
            MDC.remove(THREAD_ID);
            MDC.remove(RUN_ID);
            return;
        }
        CURRENT.set(previous);
        putMdc(previous);
    }

    public static final class Scope implements AutoCloseable {
        private final State previous;
        private boolean closed;

        private Scope(State previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                restore(previous);
            }
        }
    }
}
