package com.itajay.superassistant.workflow;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Process-wide mutual exclusion per research topic.
 *
 * <p>Two conversations can otherwise research the same folder at once: they duplicate
 * the LLM close-reading work and race on {@code analysis/*.md} and
 * {@code document/*.md}. Writes are atomic, but last-writer-wins still lets one run's
 * reviewer read the other run's draft. Both workflows take this lock for the whole
 * workflow and report "already running" instead of blocking a chat thread indefinitely.</p>
 */
public final class TopicLocks {

    private static final ConcurrentHashMap<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private TopicLocks() {
    }

    /** Returns a lock handle, or {@code null} when another run owns the topic. */
    public static Handle tryLock(String topicId) {
        if (topicId == null || topicId.isBlank()) {
            return new Handle(null);
        }
        ReentrantLock lock = LOCKS.computeIfAbsent(topicId, key -> new ReentrantLock());
        return lock.tryLock() ? new Handle(lock) : null;
    }

    public static final class Handle implements AutoCloseable {
        private final ReentrantLock lock;
        private boolean closed;

        private Handle(ReentrantLock lock) {
            this.lock = lock;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                if (lock != null) {
                    lock.unlock();
                }
            }
        }
    }
}
