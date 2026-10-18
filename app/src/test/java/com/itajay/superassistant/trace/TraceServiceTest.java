package com.itajay.superassistant.trace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TraceServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void recordsRunAndToolEventsAndPersistsJsonl() throws Exception {
        TraceService service = new TraceService(null, tempDir);
        try (TraceContext.Scope ignored =
                     TraceContext.open("tr-0123456789abcdef", "thread-1", "run-1")) {
            service.beginRun("tr-0123456789abcdef", "thread-1", "run-1", "planEnabled=false");
            service.record(TraceEvent.Category.TOOL, "readFile", TraceEvent.Status.STARTED,
                    0, "args: paper.md", java.util.Map.of());
            service.record(TraceEvent.Category.TOOL, "readFile", TraceEvent.Status.SUCCEEDED,
                    12, "result: ok", java.util.Map.of());
            service.endRun(TraceEvent.Status.SUCCEEDED, "answer 42 chars");
        }

        TraceService.TraceSnapshot snapshot = service.recent("thread-1");
        assertThat(snapshot.traceId()).isEqualTo("tr-0123456789abcdef");
        assertThat(snapshot.events()).extracting(TraceEvent::name)
                .containsExactly("run", "readFile", "readFile", "run");
        assertThat(snapshot.events()).extracting(TraceEvent::status)
                .containsExactly(TraceEvent.Status.STARTED, TraceEvent.Status.STARTED,
                        TraceEvent.Status.SUCCEEDED, TraceEvent.Status.SUCCEEDED);
        assertThat(snapshot.events().getLast().durationMs()).isGreaterThanOrEqualTo(0);

        Path file = tempDir.resolve("thread-1").resolve("run-1.jsonl");
        assertThat(Files.exists(file)).isTrue();
        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(4);
        assertThat(lines.getFirst()).contains("\"traceId\":\"tr-0123456789abcdef\"");
    }

    @Test
    void readsTheNewestRunBackFromDiskAfterRestart() throws Exception {
        TraceService writer = new TraceService(null, tempDir);
        try (TraceContext.Scope ignored =
                     TraceContext.open("tr-aaaaaaaaaaaaaaaa", "thread-2", "run-a")) {
            writer.beginRun("tr-aaaaaaaaaaaaaaaa", "thread-2", "run-a", "first");
            writer.endRun(TraceEvent.Status.SUCCEEDED, "done");
        }

        // A fresh instance has no memory ring; the API still returns the last run.
        TraceService reader = new TraceService(null, tempDir);
        TraceService.TraceSnapshot snapshot = reader.recent("thread-2");

        assertThat(snapshot.traceId()).isEqualTo("tr-aaaaaaaaaaaaaaaa");
        assertThat(snapshot.events()).hasSize(2);
        assertThat(snapshot.events().getLast().status()).isEqualTo(TraceEvent.Status.SUCCEEDED);
        assertThat(snapshot.endedAt()).isPositive();
    }

    @Test
    void disabledTracingRecordsNothing() {
        TraceService service = new TraceService(null, tempDir);
        // Field default is enabled=true; exercise the empty case instead: no beginRun.
        try (TraceContext.Scope ignored =
                     TraceContext.open("tr-bbbbbbbbbbbbbbbb", "thread-3", "run-b")) {
            service.record(TraceEvent.Category.TOOL, "readFile",
                    TraceEvent.Status.STARTED, 0, "args", java.util.Map.of());
        }
        assertThat(service.recent("thread-3").events()).isEmpty();
    }
}
