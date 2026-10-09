package com.itajay.superassistant.workflow;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewRunnerTest {

    private static final String PASS = "{\"verdict\":\"PASS\",\"issues\":[]}";

    @Test
    void aNetworkExceptionDoesNotAddAnotherRetryLayer() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> ReviewRunner.run((input, config) -> {
            calls.incrementAndGet();
            throw new IOException("network unavailable");
        }, "artifact", null, () -> {})).isInstanceOf(IOException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void anInterruptFlagOnTheRecoveryCallCannotBecomeAPass() throws Exception {
        var caller = Executors.newSingleThreadExecutor();
        AtomicInteger calls = new AtomicInteger();
        try {
            boolean interrupted = caller.submit(() -> {
                try {
                    ReviewRunner.run((input, config) -> {
                        if (calls.incrementAndGet() == 1) return "bad JSON";
                        Thread.currentThread().interrupt();
                        return PASS;
                    }, "artifact", null, () -> {});
                    return false;
                } catch (InterruptedException e) {
                    return Thread.currentThread().isInterrupted();
                }
            }).get(5, TimeUnit.SECONDS);
            assertThat(interrupted).isTrue();
            assertThat(calls).hasValue(2);
        } finally {
            caller.shutdownNow();
            assertThat(caller.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
