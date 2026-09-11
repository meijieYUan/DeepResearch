package com.itajay.superassistant.compact;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared boundary rules for assistant tool calls and their tool responses.
 *
 * A transaction starts at an AssistantMessage carrying tool calls and ends at
 * its last matching ToolResponseMessage. Compaction may place a boundary
 * before the transaction or after it, but never inside it.
 */
public final class ToolCallIntegrity {

    private ToolCallIntegrity() {}

    public static boolean isToolCall(Message message) {
        return message instanceof AssistantMessage assistantMessage
                && assistantMessage.hasToolCalls();
    }

    public static boolean isToolResponse(Message message) {
        return message instanceof ToolResponseMessage;
    }

    /**
     * Moves a prefix/suffix boundary backward to the start of any tool
     * transaction it would otherwise split. The returned index is exclusive:
     * messages before it are summarized and messages from it onward are kept.
     */
    public static int safeCutoff(List<Message> messages, int candidate) {
        if (messages == null || candidate <= 0 || candidate >= messages.size()) {
            return candidate;
        }

        Map<String, Integer> assistantIndexesByCallId = new HashMap<>();
        int safeCutoff = candidate;
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (isToolCall(message)) {
                int assistantIndex = i;
                AssistantMessage assistantMessage = (AssistantMessage) message;
                assistantMessage.getToolCalls()
                        .forEach(call -> assistantIndexesByCallId.putIfAbsent(call.id(), assistantIndex));
                continue;
            }
            if (isToolResponse(message)) {
                ToolResponseMessage responseMessage = (ToolResponseMessage) message;
                for (ToolResponseMessage.ToolResponse response : responseMessage.getResponses()) {
                    Integer assistantIndex = assistantIndexesByCallId.get(response.id());
                    if (assistantIndex != null
                            && assistantIndex < candidate
                            && candidate <= i) {
                        safeCutoff = Math.min(safeCutoff, assistantIndex);
                    }
                }
            }
        }
        return safeCutoff;
    }

    /**
     * Returns the start index of the oldest transaction among the newest keep
     * tool transactions. Callers retain every message from this index onward.
     */
    public static int recentTransactionStart(List<Message> messages, int keep) {
        if (messages == null || keep <= 0) {
            return messages == null ? 0 : messages.size();
        }

        Map<String, Integer> assistantIndexesByCallId = new HashMap<>();
        Set<Integer> transactionStarts = new LinkedHashSet<>();
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (isToolCall(message)) {
                int assistantIndex = i;
                AssistantMessage assistantMessage = (AssistantMessage) message;
                assistantMessage.getToolCalls()
                        .forEach(call -> assistantIndexesByCallId.putIfAbsent(call.id(), assistantIndex));
            } else if (isToolResponse(message)) {
                ToolResponseMessage responseMessage = (ToolResponseMessage) message;
                responseMessage.getResponses().stream()
                        .map(ToolResponseMessage.ToolResponse::id)
                        .map(assistantIndexesByCallId::get)
                        .filter(index -> index != null)
                        .forEach(transactionStarts::add);
            }
        }

        List<Integer> starts = new ArrayList<>(transactionStarts);
        if (starts.size() <= keep) {
            return 0;
        }
        return starts.get(starts.size() - keep);
    }
}
