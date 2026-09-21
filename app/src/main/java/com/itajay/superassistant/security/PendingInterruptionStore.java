package com.itajay.superassistant.security;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds interrupted runs waiting for human approval, keyed by threadId.
 *
 * <p>Entries expire after {@link #TTL}: an interruption the user never answers used
 * to pin its RunnableConfig, metadata and input message in memory forever. Expiry is
 * enforced lazily on {@link #get} and by a periodic sweep so abandoned entries do not
 * accumulate even when nobody ever calls get again.</p>
 *
 * <p>Still in-memory only: a restart loses pending approvals and multiple instances
 * cannot see each other's. Persisting to MySQL is tracked as a follow-up.</p>
 */
@Component
public class PendingInterruptionStore {

    private static final Logger log = LoggerFactory.getLogger(PendingInterruptionStore.class);

    /** How long an unanswered approval stays resumable. */
    static final Duration TTL = Duration.ofMinutes(30);

    private final ConcurrentHashMap<String, PendingInterruption> store = new ConcurrentHashMap<>();

    public void put(String threadId,
                    RunnableConfig config,
                    InterruptionMetadata metadata,
                    String inputMessage) {
        store.put(threadId, new PendingInterruption(config, metadata, inputMessage, Instant.now()));
    }

    /** Returns the pending interruption, or {@code null} when absent or expired. */
    public PendingInterruption get(String threadId) {
        PendingInterruption pending = store.get(threadId);
        if (pending != null && pending.isExpired()) {
            // Remove only the exact expired entry; a concurrent put wins over the sweep.
            store.remove(threadId, pending);
            log.info("Pending interruption expired for thread={}", threadId);
            return null;
        }
        return pending;
    }

    public void remove(String threadId) {
        store.remove(threadId);
    }

    /** Periodic sweep so entries nobody ever approves do not linger indefinitely. */
    @Scheduled(fixedDelay = 10 * 60 * 1000L)
    void sweepExpired() {
        for (Map.Entry<String, PendingInterruption> entry : store.entrySet()) {
            if (entry.getValue().isExpired()) {
                store.remove(entry.getKey(), entry.getValue());
                log.info("Swept expired pending interruption for thread={}", entry.getKey());
            }
        }
    }

    public record PendingInterruption(
            RunnableConfig config,
            InterruptionMetadata metadata,
            String inputMessage,
            Instant createdAt
    ) {
        public PendingInterruption(RunnableConfig config,
                                   InterruptionMetadata metadata,
                                   String inputMessage) {
            this(config, metadata, inputMessage, Instant.now());
        }

        boolean isExpired() {
            return Instant.now().isAfter(createdAt.plus(TTL));
        }
    }
}
