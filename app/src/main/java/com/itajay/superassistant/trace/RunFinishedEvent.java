package com.itajay.superassistant.trace;

/** Published when a chat run reaches a terminal state, so run-scoped state can be cleared. */
public record RunFinishedEvent(String runId, String threadId) {
}
