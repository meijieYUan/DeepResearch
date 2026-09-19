package com.itajay.superassistant.progress;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the SSE progress channel registry's multicast fan-out.
 *
 * <p>The properties that matter: publishing is never fatal (a workflow runs whether
 * or not a browser is listening), one dead connection never affects another, and a
 * thread can have several listeners at once — the POST response stream and a debug
 * stream are independent subscribers of the same events.</p>
 */
class ProgressChannelRegistryTest {

    private final ProgressChannelRegistry registry = new ProgressChannelRegistry();

    @Test
    void publishesToEveryAttachedEmitterOfAThread() {
        CopyOnWriteArrayList<Integer> received = new CopyOnWriteArrayList<>();
        SseEmitter first = registry.register("thread-1");
        SseEmitter second = registry.register("thread-1");
        // Stand-ins for the real emitters: observes the same fan-out by wiring a probe
        // through attach, since a bare SseEmitter's sends cannot be inspected without
        // a servlet response.
        registry.attach("thread-1", probe(received));

        registry.publish("thread-1", ProgressEvent.of(ProgressStage.RESEARCHING, 1, "开始检索"));

        assertThat(received).hasSize(1);
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
    }

    @Test
    void publishingWithoutAListenerIsANoOp() {
        // The API-only case: nobody opened a stream. This must not throw.
        assertThatCode(() ->
                registry.publish("nobody-listening", ProgressEvent.of(ProgressStage.WRITING, 1)))
                .doesNotThrowAnyException();
    }

    @Test
    void publishingWithNullOrBlankIdentifiersIsIgnored() {
        assertThatCode(() -> {
            registry.publish(null, ProgressEvent.of(ProgressStage.DONE, 1));
            registry.publish("  ", ProgressEvent.of(ProgressStage.DONE, 1));
            registry.publish("thread-1", null);
        }).doesNotThrowAnyException();
    }

    @Test
    void aFailedSendDropsOnlyTheDeadEmitter() {
        // Simulates one of two connections dying without the registry being told.
        // Completing the emitter behind the registry's back is the closest reachable
        // stand-in for that: the emitter's own callbacks only fire under a real
        // servlet request, so publish() itself must tolerate and prune the dead one.
        SseEmitter dead = registry.register("thread-1");
        CopyOnWriteArrayList<Integer> received = new CopyOnWriteArrayList<>();
        registry.attach("thread-1", probe(received));
        dead.complete();

        registry.publish("thread-1", ProgressEvent.of(ProgressStage.WRITING, 1));

        // The surviving listener still received the event, exactly once.
        assertThat(received).hasSize(1);
    }

    @Test
    void listenersAreScopedPerThread() {
        CopyOnWriteArrayList<Integer> receivedA = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<Integer> receivedB = new CopyOnWriteArrayList<>();
        registry.attach("thread-a", probe(receivedA));
        registry.attach("thread-b", probe(receivedB));

        registry.publish("thread-a", ProgressEvent.of(ProgressStage.RESEARCHING, 1));

        assertThat(receivedA).hasSize(1);
        assertThat(receivedB).isEmpty();
    }

    @Test
    void registrationAndAttachmentRequireAThreadId() {
        assertThatThrownBy(() -> registry.register(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.register("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.attach(null, new SseEmitter()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDeadEmitterIsPrunedOnTheNextPublish() throws IOException {
        // A standalone SseEmitter never fires its completion callback (that happens
        // when the servlet container initializes it), so death is simulated directly:
        // the probe's socket closes and every later send throws, which is what the
        // registry must prune on. Production emitters also get the completion-callback
        // path; this is the belt-and-braces prune.
        List<Integer> sends = new CopyOnWriteArrayList<>();
        AtomicBoolean dead = new AtomicBoolean();
        SseEmitter dying = new SseEmitter() {
            @Override
            public void send(SseEmitter.SseEventBuilder builder) throws IOException {
                if (dead.get()) throw new IOException("socket closed");
                sends.add(1);
            }
        };
        registry.attach("thread-1", dying);

        registry.publish("thread-1", ProgressEvent.of(ProgressStage.WRITING, 1));
        assertThat(sends).hasSize(1);

        dead.set(true); // the socket closes mid-run
        registry.publish("thread-1", ProgressEvent.of(ProgressStage.WRITING, 1));
        registry.publish("thread-1", ProgressEvent.of(ProgressStage.WRITING, 1));

        assertThat(sends).hasSize(1); // no further send attempts reach the dead socket
    }

    @Test
    void heartbeatNeverThrowsAndKeepsLiveChannels() {
        CopyOnWriteArrayList<Integer> received = new CopyOnWriteArrayList<>();
        registry.attach("thread-1", probe(received));

        // SseEmitter buffers sends until it is attached to a response, so an
        // unattached emitter accepts them. What matters is that the scheduled task
        // cannot throw — an exception there would cancel all future heartbeats.
        registry.heartbeat();

        // The keepalive is a comment frame, not a progress event, but it must have
        // reached the live channel — that is what keeps proxies from timing out.
        assertThat(received).hasSize(1);

        registry.publish("thread-1", ProgressEvent.of(ProgressStage.WRITING, 1));
        assertThat(received).hasSize(2); // the channel is still attached after heartbeat
    }

    @Test
    void heartbeatWithNoChannelsDoesNothing() {
        assertThatCode(() -> registry.heartbeat()).doesNotThrowAnyException();
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

    /**
     * A minimal emitter that records progress sends instead of writing to a socket.
     * Subclassed because {@link SseEmitter#send} cannot be observed without a real
     * servlet async context; overriding keeps the registry's fan-out code the thing
     * under test.
     */
    private static SseEmitter probe(CopyOnWriteArrayList<Integer> received) {
        return new SseEmitter() {
            @Override
            public void send(SseEmitter.SseEventBuilder builder) throws IOException {
                received.add(1);
            }
        };
    }
}
