package com.itajay.superassistant.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itajay.superassistant.progress.ProgressChannelRegistry;
import com.itajay.superassistant.workspace.WorkspacePaths;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Records and streams a run's execution trace.
 *
 * <p>Two sinks with different lifetimes:</p>
 * <ul>
 *   <li><b>SSE</b> — every event is pushed as a {@code trace} frame on the same
 *       connection as the answer, so the UI can show what is running right now.</li>
 *   <li><b>JSONL on disk</b> — {@code .trace/{threadId}/{runId}.jsonl}, one event per
 *       line, kept for post-mortem. A memory ring per thread serves the trace API
 *       without touching disk on every request; after a restart the newest file is
 *       read back.</li>
 * </ul>
 *
 * <p>Recording is best-effort and never throws into the run: a broken trace must
 * not break the conversation. The log line for each event is emitted under the
 * current trace MDC, so console/file logs and the UI share the same ids.</p>
 */
@Service
public class TraceService {

    private static final Logger log = LoggerFactory.getLogger(TraceService.class);

    @Value("${agent.trace.enabled:true}")
    private boolean enabled = true;

    @Value("${agent.trace.max-events:500}")
    private int maxEvents = 500;

    @Value("${agent.trace.retain-runs:20}")
    private int retainRuns = 20;

    /** Optional so unit tests and reduced contexts can construct the service. */
    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    private final ProgressChannelRegistry channels;
    private final ApplicationEventPublisher events;
    private final Path root;
    private final ObjectMapper mapper = new ObjectMapper();
    /** One active run per thread; ChatStreamingService enforces that invariant. */
    private final Map<String, RunState> runs = new ConcurrentHashMap<>();

    @Autowired
    public TraceService(ProgressChannelRegistry channels, ApplicationEventPublisher events) {
        this(channels, WorkspacePaths.root().resolve(".trace"), events);
    }

    TraceService(ProgressChannelRegistry channels, Path root) {
        this(channels, root, null);
    }

    TraceService(ProgressChannelRegistry channels, Path root, ApplicationEventPublisher events) {
        this.channels = channels;
        this.events = events;
        this.root = root.toAbsolutePath().normalize();
    }

    @PostConstruct
    void logConfiguration() {
        log.info("Execution traces: enabled={}, dir={}, maxEvents={}, retainRuns={}",
                enabled, root, maxEvents, retainRuns);
    }

    /** Starts a run and records its RUN/STARTED event. */
    public void beginRun(String traceId, String threadId, String runId, String detail) {
        if (!enabled || threadId == null || runId == null) {
            return;
        }
        RunState run = new RunState(traceId, runId, threadDir(threadId).resolve(runId + ".jsonl"));
        run.startedAt = System.currentTimeMillis();
        runs.put(threadId, run);
        prune(run.file.getParent());
        record(new TraceContext.State(traceId, threadId, runId),
                TraceEvent.Category.RUN, "run", TraceEvent.Status.STARTED,
                0, detail, Map.of());
    }

    /** Finishes the current run; the run stays in memory for the trace API. */
    public void endRun(TraceEvent.Status status, String detail) {
        TraceContext.State state = TraceContext.current();
        if (!enabled || state == null) {
            return;
        }
        RunState run = runs.get(state.threadId());
        if (run == null) {
            return;
        }
        long duration = Math.max(0, System.currentTimeMillis() - run.startedAt);
        run.endedAt = System.currentTimeMillis();
        record(state, TraceEvent.Category.RUN, "run", status, duration, detail, Map.of());
        if (events != null) {
            events.publishEvent(new RunFinishedEvent(run.runId, state.threadId()));
        }
    }

    /** Records an event for the current trace context, if any. */
    public void record(TraceEvent.Category category, String name, TraceEvent.Status status,
                       long durationMs, String detail, Map<String, Object> attributes) {
        TraceContext.State state = TraceContext.current();
        if (state != null) {
            record(state, category, name, status, durationMs, detail, attributes);
        }
    }

    /** Records an event for an explicitly resolved context (interceptor fallback). */
    public void record(TraceContext.State state, TraceEvent.Category category, String name,
                       TraceEvent.Status status, long durationMs, String detail,
                       Map<String, Object> attributes) {
        if (!enabled || state == null || state.threadId() == null) {
            return;
        }
        RunState run = runs.get(state.threadId());
        if (run == null) {
            return;
        }
        TraceEvent event = new TraceEvent(
                state.traceId() == null ? run.traceId : state.traceId(),
                state.threadId(),
                run.runId,
                run.seq.incrementAndGet(),
                System.currentTimeMillis(),
                category,
                name == null ? category.name().toLowerCase() : name,
                status,
                Math.max(0, durationMs),
                truncate(detail, 500),
                attributes == null ? Map.of() : attributes);

        synchronized (run.events) {
            run.events.addLast(event);
            while (run.events.size() > Math.max(10, maxEvents)) {
                run.events.removeFirst();
            }
        }
        append(run, event);
        recordMetrics(event);
        if (channels != null) {
            channels.publishTrace(state.threadId(), event);
        }
        logEvent(event, state);
    }

    private void recordMetrics(TraceEvent event) {
        // Count completed operations only: counting STARTED as well made one run
        // look like two "agent.runs" with status STARTED/SUCCEEDED.
        if (meterRegistry == null || event.status() == TraceEvent.Status.STARTED) {
            return;
        }
        String status = event.status().name();
        switch (event.category()) {
            case TOOL -> {
                meterRegistry.counter("agent.tool.calls",
                        "name", event.name(), "status", status).increment();
                if (event.durationMs() > 0) {
                    meterRegistry.timer("agent.tool.duration", "name", event.name())
                            .record(event.durationMs(), TimeUnit.MILLISECONDS);
                }
            }
            case MODEL -> {
                meterRegistry.counter("agent.model.calls", "status", status).increment();
                if (event.durationMs() > 0) {
                    meterRegistry.timer("agent.model.duration")
                            .record(event.durationMs(), TimeUnit.MILLISECONDS);
                }
            }
            case RUN -> {
                meterRegistry.counter("agent.runs", "status", status).increment();
                if (event.durationMs() > 0) {
                    meterRegistry.timer("agent.run.duration")
                            .record(event.durationMs(), TimeUnit.MILLISECONDS);
                }
            }
            default -> {
                // WORKFLOW/ERROR have no dedicated meter yet.
            }
        }
    }

    /** Latest run for a thread, from memory, or from the newest JSONL after a restart. */
    public TraceSnapshot recent(String threadId) {
        RunState run = threadId == null ? null : runs.get(threadId);
        if (run != null) {
            List<TraceEvent> events;
            synchronized (run.events) {
                events = new ArrayList<>(run.events);
            }
            return new TraceSnapshot(run.traceId, run.runId, run.startedAt, run.endedAt, events);
        }
        return readNewest(threadId);
    }

    private TraceSnapshot readNewest(String threadId) {
        if (threadId == null) {
            return TraceSnapshot.empty();
        }
        Path dir = threadDir(threadId);
        if (!Files.isDirectory(dir)) {
            return TraceSnapshot.empty();
        }
        try (Stream<Path> files = Files.list(dir)) {
            Path newest = files
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .max(Comparator.comparingLong(TraceService::lastModified))
                    .orElse(null);
            if (newest == null) {
                return TraceSnapshot.empty();
            }
            List<String> lines = Files.readAllLines(newest, StandardCharsets.UTF_8);
            List<TraceEvent> events = new ArrayList<>();
            for (String line : lines) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    events.add(mapper.readValue(line, TraceEvent.class));
                } catch (Exception e) {
                    // A truncated final line is expected after a crash; skip it.
                    log.debug("Skipping malformed trace line in {}: {}", newest, e.getMessage());
                }
            }
            if (events.size() > Math.max(10, maxEvents)) {
                events = new ArrayList<>(events.subList(events.size() - Math.max(10, maxEvents), events.size()));
            }
            if (events.isEmpty()) {
                return TraceSnapshot.empty();
            }
            TraceEvent first = events.getFirst();
            TraceEvent last = events.getLast();
            long endedAt = last.category() == TraceEvent.Category.RUN
                    && last.status() != TraceEvent.Status.STARTED ? last.timestamp() : 0;
            return new TraceSnapshot(first.traceId(), first.runId(), first.timestamp(), endedAt, events);
        } catch (IOException e) {
            log.warn("Could not read trace for thread {}: {}", threadId, e.getMessage());
            return TraceSnapshot.empty();
        }
    }

    private void append(RunState run, TraceEvent event) {
        try {
            Files.createDirectories(run.file.getParent());
            String line = mapper.writeValueAsString(event) + "\n";
            synchronized (run.writeLock) {
                Files.writeString(run.file, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (Exception e) {
            // Tracing must never affect the run.
            log.warn("Could not persist trace event for run {}: {}", run.runId, e.getMessage());
        }
    }

    /** Keeps only the newest {@code retainRuns} trace files per thread. */
    private void prune(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> sorted = files
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .sorted(Comparator.comparingLong(TraceService::lastModified).reversed())
                    .toList();
            for (int i = Math.max(1, retainRuns); i < sorted.size(); i++) {
                Files.deleteIfExists(sorted.get(i));
            }
        } catch (IOException e) {
            log.debug("Could not prune trace directory {}: {}", dir, e.getMessage());
        }
    }

    /** Safe directory per thread; a pathological id must not break the run. */
    private Path threadDir(String threadId) {
        String slug;
        try {
            slug = WorkspacePaths.slugify(threadId, "thread");
        } catch (IllegalArgumentException e) {
            slug = "thread";
        }
        return root.resolve(slug);
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String value = text.strip();
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static void logEvent(TraceEvent event, TraceContext.State state) {
        try (TraceContext.Scope ignored = TraceContext.current() == null
                ? TraceContext.open(state) : null) {
            if (event.status() == TraceEvent.Status.FAILED || event.category() == TraceEvent.Category.ERROR) {
                log.error("[trace] {} {} failed in {}ms: {}", event.category(), event.name(),
                        event.durationMs(), event.detail());
            } else if (event.category() == TraceEvent.Category.RUN) {
                log.info("[trace] run {} {} ({}ms): {}", event.runId(), event.status(),
                        event.durationMs(), event.detail());
            } else if (event.category() == TraceEvent.Category.TOOL) {
                // Tool boundaries are the useful grep anchor when diagnosing a run;
                // model calls are left at DEBUG because there are far more of them.
                log.info("[trace] tool {} {} ({}ms): {}", event.name(), event.status(),
                        event.durationMs(), event.detail());
            } else {
                log.debug("[trace] {} {} {} ({}ms): {}", event.category(), event.name(),
                        event.status(), event.durationMs(), event.detail());
            }
        }
    }

    /** The trace payload returned by the API and rendered by the frontend. */
    public record TraceSnapshot(String traceId, String runId, long startedAt, long endedAt,
                                List<TraceEvent> events) {
        public static TraceSnapshot empty() {
            return new TraceSnapshot(null, null, 0, 0, List.of());
        }
    }

    private static final class RunState {
        private final String traceId;
        private final String runId;
        private final Path file;
        private final AtomicLong seq = new AtomicLong();
        private final ArrayDeque<TraceEvent> events = new ArrayDeque<>();
        private final Object writeLock = new Object();
        private volatile long startedAt;
        private volatile long endedAt;

        private RunState(String traceId, String runId, Path file) {
            this.traceId = traceId;
            this.runId = runId;
            this.file = file;
        }
    }
}
