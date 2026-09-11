package com.itajay.superassistant.compact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Layer 3: MicroCompact — replace stale early tool-result payloads.
 *
 * The recent tool transactions are retained verbatim. Older transactions keep
 * their assistant calls and IDs, but their result payloads are replaced with a
 * short marker so narrative history and tool-call integrity are preserved.
 */
public final class MicroCompact {

    private static final Logger log = LoggerFactory.getLogger(MicroCompact.class);

    private MicroCompact() {}

    /**
     * Apply micro-compaction while preserving the original message sequence.
     */
    public static List<Message> compact(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return messages;

        int keep = CompactConfig.MICROCOMPACT_KEEP_RECENT_TOOL_PAIRS;
        int cutoff = ToolCallIntegrity.recentTransactionStart(messages, keep);
        if (cutoff <= 0) {
            return messages;
        }

        Map<String, Integer> assistantIndexesByCallId = buildCallIndex(messages);
        List<Message> result = new ArrayList<>(messages.size() + 1);
        int replacedResults = 0;

        for (Message message : messages) {
            if (message instanceof ToolResponseMessage responseMessage) {
                ToolResponseMessage replacement = replaceOldResponses(
                        responseMessage, assistantIndexesByCallId, cutoff);
                if (replacement != responseMessage) {
                    replacedResults++;
                    result.add(replacement);
                } else {
                    result.add(message);
                }
            } else {
                result.add(message);
            }
        }

        if (replacedResults == 0) {
            return messages;
        }

        result.add(0, new SystemMessage(
                "[MicroCompact: " + replacedResults
                + " stale tool result payload(s) replaced. The most recent "
                + keep + " tool transaction(s) are preserved.]"
        ));
        log.info("MicroCompact: replaced {} stale tool result payload(s), kept {} recent transactions",
                replacedResults, keep);
        return List.copyOf(result);
    }

    private static Map<String, Integer> buildCallIndex(List<Message> messages) {
        Map<String, Integer> assistantIndexesByCallId = new HashMap<>();
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (ToolCallIntegrity.isToolCall(message)) {
                int assistantIndex = i;
                AssistantMessage assistantMessage = (AssistantMessage) message;
                assistantMessage.getToolCalls()
                        .forEach(call -> assistantIndexesByCallId.putIfAbsent(call.id(), assistantIndex));
            }
        }
        return assistantIndexesByCallId;
    }

    private static ToolResponseMessage replaceOldResponses(
            ToolResponseMessage message,
            Map<String, Integer> assistantIndexesByCallId,
            int cutoff) {
        List<ToolResponseMessage.ToolResponse> replacements = new ArrayList<>();
        boolean changed = false;

        for (ToolResponseMessage.ToolResponse response : message.getResponses()) {
            Integer assistantIndex = assistantIndexesByCallId.get(response.id());
            if (assistantIndex != null && assistantIndex < cutoff
                    && !isCompacted(response.responseData())) {
                replacements.add(new ToolResponseMessage.ToolResponse(
                        response.id(), response.name(),
                        "[MicroCompact: stale tool result payload removed. Original call id: "
                                + response.id() + ".]"));
                changed = true;
            } else {
                replacements.add(response);
            }
        }

        if (!changed) {
            return message;
        }
        return ToolResponseMessage.builder()
                .responses(replacements)
                .metadata(message.getMetadata())
                .build();
    }

    private static boolean isCompacted(String content) {
        return content != null && content.startsWith("[MicroCompact:");
    }
}
