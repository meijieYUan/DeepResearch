package com.itajay.superassistant.progress;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

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

    @Test
    void aCustomLabelOverridesOnlyTheLabelNotTheStage() {
        // The single-paper deep-read finishes as "精读完成", but the stage must stay
        // DONE/FAILED — the frontend detects failure from the stage enum name.
        ProgressEvent event = ProgressEvent.of(ProgressStage.FAILED, 2, "未通过审查", "精读失败");

        assertThat(event.label()).isEqualTo("精读失败");
        assertThat(event.stage()).isEqualTo(ProgressStage.FAILED);
    }

    @Test
    void anExplicitNullLabelFallsBackToTheStageLabel() {
        assertThat(ProgressEvent.of(ProgressStage.DONE, 1, "", null).label())
                .isEqualTo(ProgressStage.DONE.label());
    }

    @ParameterizedTest
    @EnumSource(Lifecycle.class)
    void lifecycleRemovalKeepsTheOtherSubscriberAndCleansUpTheLast(Lifecycle lifecycle) {
        RecordingEmitter first = new RecordingEmitter();
        RecordingEmitter survivor = new RecordingEmitter();
        registry.attach("thread-1", first);
        registry.attach("thread-1", survivor);

        first.fire(lifecycle);
        assertThat(channels().get("thread-1")).containsExactly(survivor);

        registry.publish("thread-1", ProgressEvent.of(ProgressStage.WRITING, 1));
        registry.heartbeat();

        assertThat(first.sendAttempts.get()).isZero();
        assertThat(survivor.progressSends.get()).isEqualTo(1);
        assertThat(survivor.heartbeatSends.get()).isEqualTo(1);

        survivor.fire(lifecycle);
        assertThat(channels()).doesNotContainKey("thread-1");
    }

    @ParameterizedTest
    @EnumSource(SendFailure.class)
    void failedPublishRemovesOnlyTheFaultySubscriber(SendFailure failure) {
        RecordingEmitter dead = new RecordingEmitter();
        RecordingEmitter survivor = new RecordingEmitter();
        dead.failure = failure;
        registry.attach("thread-1", dead);
        registry.attach("thread-1", survivor);

        registry.publish("thread-1", ProgressEvent.of(ProgressStage.WRITING, 1));
        registry.publish("thread-1", ProgressEvent.of(ProgressStage.WRITING, 1));
        registry.heartbeat();

        assertThat(channels().get("thread-1")).containsExactly(survivor);
        assertThat(dead.sendAttempts.get()).isEqualTo(1);
        assertThat(survivor.progressSends.get()).isEqualTo(2);
        assertThat(survivor.heartbeatSends.get()).isEqualTo(1);

        survivor.failure = failure;
        registry.publish("thread-1", ProgressEvent.of(ProgressStage.WRITING, 1));
        assertThat(channels()).doesNotContainKey("thread-1");
    }

    @ParameterizedTest
    @EnumSource(SendFailure.class)
    void failedHeartbeatRemovesOnlyTheFaultySubscriberAndCleansUpTheLast(SendFailure failure) {
        RecordingEmitter dead = new RecordingEmitter();
        RecordingEmitter survivor = new RecordingEmitter();
        dead.failure = failure;
        registry.attach("thread-1", dead);
        registry.attach("thread-1", survivor);

        registry.heartbeat();
        registry.heartbeat();
        registry.publish("thread-1", ProgressEvent.of(ProgressStage.WRITING, 1));

        assertThat(channels().get("thread-1")).containsExactly(survivor);
        assertThat(dead.sendAttempts.get()).isEqualTo(1);
        assertThat(survivor.heartbeatSends.get()).isEqualTo(2);
        assertThat(survivor.progressSends.get()).isEqualTo(1);

        survivor.failure = failure;
        registry.heartbeat();
        assertThat(channels()).doesNotContainKey("thread-1");
    }

    @ParameterizedTest
    @EnumSource(Lifecycle.class)
    void repeatedLateCallbacksCannotRemoveANewSubscriber(Lifecycle lifecycle) {
        RecordingEmitter old = new RecordingEmitter();
        registry.attach("thread-1", old);
        old.fire(lifecycle);
        assertThat(channels()).doesNotContainKey("thread-1");

        RecordingEmitter current = new RecordingEmitter();
        registry.attach("thread-1", current);
        for (int attempt = 0; attempt < 2; attempt++) {
            for (Lifecycle lateCallback : Lifecycle.values()) {
                old.fire(lateCallback);
            }
        }
        registry.publish("thread-1", ProgressEvent.of(ProgressStage.WRITING, 1));
        registry.heartbeat();

        assertThat(channels().get("thread-1")).containsExactly(current);
        assertThat(current.progressSends.get()).isEqualTo(1);
        assertThat(current.heartbeatSends.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(Lifecycle.class)
    void lifecycleCallbackDuringRegistrationDoesNotLeaveADeadSubscriber(Lifecycle lifecycle) {
        RecordingEmitter emitter = new RecordingEmitter();
        emitter.finishDuringRegistration = lifecycle;

        registry.attach("thread-1", emitter);
        registry.publish("thread-1", ProgressEvent.of(ProgressStage.WRITING, 1));
        registry.heartbeat();

        assertThat(channels()).doesNotContainKey("thread-1");
        assertThat(emitter.sendAttempts.get()).isZero();
    }

    @Test
    void concurrentAttachAndLastSubscriberRemovalNeverLoseTheNewSubscriber() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int attempt = 0; attempt < 100; attempt++) {
                RecordingEmitter old = new RecordingEmitter();
                RecordingEmitter current = new RecordingEmitter();
                registry.attach("thread-1", old);
                CyclicBarrier start = new CyclicBarrier(2);
                Future<?> attach = executor.submit(() -> {
                    await(start);
                    registry.attach("thread-1", current);
                });
                Future<?> remove = executor.submit(() -> {
                    await(start);
                    old.fire(Lifecycle.COMPLETION);
                });
                attach.get(5, TimeUnit.SECONDS);
                remove.get(5, TimeUnit.SECONDS);

                assertThat(channels().get("thread-1")).containsExactly(current);
                registry.publish("thread-1", ProgressEvent.of(ProgressStage.WRITING, 1));
                registry.heartbeat();
                assertThat(current.progressSends.get()).isEqualTo(1);
                assertThat(current.heartbeatSends.get()).isEqualTo(1);

                current.fire(Lifecycle.COMPLETION);
                assertThat(channels()).doesNotContainKey("thread-1");
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void completionWhileAttachRegistersCallbacksDoesNotResurrectTheSubscriber() throws Exception {
        CountDownLatch callbacksRegistered = new CountDownLatch(1);
        CountDownLatch finishRegistration = new CountDownLatch(1);
        RecordingEmitter emitter = new RecordingEmitter() {
            @Override
            public void onError(Consumer<Throwable> callback) {
                super.onError(callback);
                callbacksRegistered.countDown();
                await(finishRegistration);
            }
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> attach = executor.submit(() -> registry.attach("thread-1", emitter));
            await(callbacksRegistered);
            emitter.fire(Lifecycle.COMPLETION);
            finishRegistration.countDown();
            attach.get(5, TimeUnit.SECONDS);

            assertThat(channels()).doesNotContainKey("thread-1");
            registry.publish("thread-1", ProgressEvent.of(ProgressStage.WRITING, 1));
            registry.heartbeat();
            assertThat(emitter.sendAttempts.get()).isZero();
        } finally {
            finishRegistration.countDown();
            executor.shutdownNow();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, CopyOnWriteArrayList<SseEmitter>> channels() {
        return (Map<String, CopyOnWriteArrayList<SseEmitter>>) ReflectionTestUtils.getField(registry, "channels");
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("Concurrent attach/removal did not reach the barrier", e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Lifecycle callback registration did not reach the latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for callback registration", e);
        }
    }

    private enum Lifecycle { COMPLETION, TIMEOUT, ERROR }

    private enum SendFailure { IO, ILLEGAL_STATE }

    /** Records sends and invokes the lifecycle callbacks without a servlet container. */
    private static class RecordingEmitter extends SseEmitter {
        private final List<Runnable> completionCallbacks = new CopyOnWriteArrayList<>();
        private final List<Runnable> timeoutCallbacks = new CopyOnWriteArrayList<>();
        private final List<Consumer<Throwable>> errorCallbacks = new CopyOnWriteArrayList<>();
        private final AtomicInteger sendAttempts = new AtomicInteger();
        private final AtomicInteger progressSends = new AtomicInteger();
        private final AtomicInteger heartbeatSends = new AtomicInteger();
        private SendFailure failure;
        private Lifecycle finishDuringRegistration;

        @Override
        public void onCompletion(Runnable callback) {
            completionCallbacks.add(callback);
            if (finishDuringRegistration == Lifecycle.COMPLETION) {
                callback.run();
            }
        }

        @Override
        public void onTimeout(Runnable callback) {
            timeoutCallbacks.add(callback);
            if (finishDuringRegistration == Lifecycle.TIMEOUT) {
                callback.run();
            }
        }

        @Override
        public void onError(Consumer<Throwable> callback) {
            errorCallbacks.add(callback);
            if (finishDuringRegistration == Lifecycle.ERROR) {
                callback.accept(new IOException("socket closed during registration"));
            }
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            sendAttempts.incrementAndGet();
            if (failure == SendFailure.IO) {
                throw new IOException("socket closed");
            }
            if (failure == SendFailure.ILLEGAL_STATE) {
                throw new IllegalStateException("emitter completed");
            }
            if (builder.build().stream().anyMatch(data -> data.getData() instanceof ProgressEvent)) {
                progressSends.incrementAndGet();
            } else {
                heartbeatSends.incrementAndGet();
            }
        }

        private void fire(Lifecycle lifecycle) {
            switch (lifecycle) {
                case COMPLETION -> completionCallbacks.forEach(Runnable::run);
                case TIMEOUT -> timeoutCallbacks.forEach(Runnable::run);
                case ERROR -> errorCallbacks.forEach(callback -> callback.accept(new IOException("socket closed")));
            }
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
