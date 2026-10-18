package com.itajay.superassistant.workflow;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TopicLocksTest {

    @Test
    void oneRunOwnsATopicAndAnotherIsRejectedUntilRelease() throws Exception {
        String topic = "topic-lock-test-" + System.nanoTime();
        TopicLocks.Handle first = TopicLocks.tryLock(topic);
        assertThat(first).isNotNull();

        // A different thread simulates the second concurrent run; ReentrantLock would
        // (correctly) let the same thread re-enter.
        AtomicReference<TopicLocks.Handle> other = new AtomicReference<>();
        Thread second = new Thread(() -> other.set(TopicLocks.tryLock(topic)));
        second.start();
        second.join();
        assertThat(other.get()).isNull();

        first.close();
        TopicLocks.Handle again = TopicLocks.tryLock(topic);
        assertThat(again).isNotNull();
        again.close();
    }
}
