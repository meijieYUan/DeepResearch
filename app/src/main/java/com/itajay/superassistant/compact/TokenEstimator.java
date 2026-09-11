package com.itajay.superassistant.compact;

import org.springframework.ai.chat.messages.Message;

/**
 * Pluggable token estimation for the compaction pipeline.
 */
public interface TokenEstimator {

    int estimate(String text);

    int estimate(Message message);

    int estimate(Iterable<?> messages);
}
