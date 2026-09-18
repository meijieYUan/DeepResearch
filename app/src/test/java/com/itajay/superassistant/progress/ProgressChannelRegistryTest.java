package com.itajay.superassistant.progress;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the SSE progress channel registry.
 *
 * <p>The property that matters is that publishing is never fatal. A workflow runs
 * whether or not a browser is listening, and a client disconnecting mid-run must
 * not take the run down with it.</p>
 */
class ProgressChannelRegistryTest {

    private final ProgressChannelRegistry registry = new ProgressChannelRegistry();

    @Test
    void publishesToARegisteredChannel() {
        SseEmitter emitter = registry.register("thread-1");
        assertThat(registry.isConnected("thread-1")).isTrue();

        // No client is actually attached to this emitter, so send() would fail on a
        // real socket. The registry must swallow that rather than propagate it.
        registry.publish("thread-1", ProgressEvent.of(ProgressStage.RESEARCHING, 1, "开始检索"));

        assertThat(emitter).isNotNull();
    }

    @Test
    void publishingWithoutAListenerIsANoOp() {
        // The API-only case: nobody opened a stream. This must not throw.
        registry.publish("nobody-listening", ProgressEvent.of(ProgressStage.WRITING, 1));

        assertThat(registry.isConnected("nobody-listening")).isFalse();
    }

    @Test
    void publishingWithNullOrBlankIdentifiersIsIgnored() {
        registry.publish(null, ProgressEvent.of(ProgressStage.DONE, 1));
        registry.publish("  ", ProgressEvent.of(ProgressStage.DONE, 1));
        registry.publish("thread-1", null);

        assertThat(registry.isConnected("thread-1")).isFalse();
    }

    @Test
    void completeRemovesTheChannel() {
        registry.register("thread-1");
        registry.complete("thread-1");

        assertThat(registry.isConnected("thread-1")).isFalse();
    }

    @Test
    void completingAnUnknownChannelIsHarmless() {
        registry.complete("never-registered");
        registry.complete(null);

        assertThat(registry.isConnected("never-registered")).isFalse();
    }

    @Test
    void channelsAreScopedPerThread() {
        registry.register("thread-a");
        registry.register("thread-b");

        assertThat(registry.isConnected("thread-a")).isTrue();
        assertThat(registry.isConnected("thread-b")).isTrue();

        registry.complete("thread-a");

        assertThat(registry.isConnected("thread-a")).isFalse();
        assertThat(registry.isConnected("thread-b")).isTrue();
    }

    @Test
    void aSecondRegistrationReplacesTheFirst() {
        registry.register("thread-1");
        registry.register("thread-1");

        assertThat(registry.isConnected("thread-1")).isTrue();
    }

    @Test
    void registrationRequiresAThreadId() {
        assertThatThrownBy(() -> registry.register(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.register("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void heartbeatNeverThrowsAndKeepsTheChannel() {
        registry.register("thread-1");

        // SseEmitter buffers sends until it is attached to a response, so an
        // unattached emitter accepts them. What matters is that the scheduled task
        // cannot throw — an exception there would cancel all future heartbeats.
        registry.heartbeat();

        assertThat(registry.isConnected("thread-1")).isTrue();
    }

    @Test
    void heartbeatWithNoChannelsDoesNothing() {
        registry.heartbeat();

        assertThat(registry.isConnected("anything")).isFalse();
    }

    @Test
    void aFailedSendDropsTheChannelInsteadOfThrowing() {
        // Simulates a connection that died without the registry being told. Completing
        // the emitter behind the registry's back is the closest reachable stand-in for
        // that: the emitter's own callbacks only fire under a real servlet request.
        SseEmitter emitter = registry.register("thread-1");
        emitter.complete();

        registry.publish("thread-1", ProgressEvent.of(ProgressStage.WRITING, 1));

        assertThat(registry.isConnected("thread-1")).isFalse();
    }

    @Test
    void eventsCarryTheStageLabelAndTimestamp() {
        ProgressEvent event = ProgressEvent.of(ProgressStage.WRITING, 2, "按审查意见修订文档");

        assertThat(event.stage()).isEqualTo(ProgressStage.WRITING);
        assertThat(event.label()).isEqualTo(ProgressStage.WRITING.label());
        assertThat(event.round()).isEqualTo(2);
        assertThat(event.detail()).isEqualTo("按审查意见修订文档");
        assertThat(event.timestamp()).isPositive();
    }

    @Test
    void aMissingDetailBecomesAnEmptyString() {
        // The client renders `detail` directly; null would print as "null".
        assertThat(ProgressEvent.of(ProgressStage.DONE, 1).detail()).isEmpty();
        assertThat(ProgressEvent.of(ProgressStage.DONE, 1, null).detail()).isEmpty();
    }

    @Test
    void everyStageHasALabel() {
        for (ProgressStage stage : ProgressStage.values()) {
            assertThat(stage.label()).as("%s needs a display label", stage).isNotBlank();
        }
    }
}
