package com.itajay.superassistant.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TraceContextTest {

    @AfterEach
    void clear() {
        MDC.clear();
        TraceContext.current();
    }

    @Test
    void openSetsAndRestoresThreadAndMdcState() {
        try (TraceContext.Scope ignored = TraceContext.open("tr-12345678", "thread-1", "run-1")) {
            assertThat(TraceContext.current().traceId()).isEqualTo("tr-12345678");
            assertThat(TraceContext.current().threadId()).isEqualTo("thread-1");
            assertThat(MDC.get(TraceContext.TRACE_ID)).isEqualTo("tr-12345678");
            assertThat(MDC.get(TraceContext.THREAD_ID)).isEqualTo("thread-1");
            assertThat(MDC.get(TraceContext.RUN_ID)).isEqualTo("run-1");
        }
        assertThat(TraceContext.current()).isNull();
        assertThat(MDC.get(TraceContext.TRACE_ID)).isNull();
    }

    @Test
    void wrappedSupplierCarriesTheContextIntoAnotherThread() throws Exception {
        AtomicReference<TraceContext.State> seen = new AtomicReference<>();
        try (TraceContext.Scope ignored = TraceContext.open("tr-abcdefgh", "thread-1", "run-1")) {
            Thread worker = new Thread(() -> seen.set(TraceContext.current()));
            worker.start();
            worker.join();

            Thread wrapped = new Thread(TraceContext.wrapRunnable(
                    () -> seen.set(TraceContext.current())));
            wrapped.start();
            wrapped.join();
        }
        // A raw thread does not inherit thread-locals; the wrapped one does.
        assertThat(seen.get().traceId()).isEqualTo("tr-abcdefgh");
        assertThat(seen.get().threadId()).isEqualTo("thread-1");
    }

    @Test
    void hostileOrMissingIdsAreReplaced() {
        assertThat(TraceContext.normalize(null)).startsWith("tr-");
        assertThat(TraceContext.normalize("bad id with spaces")).startsWith("tr-");
        assertThat(TraceContext.normalize("../../etc/passwd")).startsWith("tr-");
        assertThat(TraceContext.normalize("tr-0123456789abcdef")).isEqualTo("tr-0123456789abcdef");
    }
}
