package com.itajay.superassistant.compact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Layer 2: Snip removes only high-confidence low-value history.
 *
 * Review contract: every removal is represented by SnipRemoval, logged with
 * its original index and reason, and replaced by one short boundary marker.
 * Tool-call assistants and tool responses are absolute protected messages.
 */
public final class ContextSnip {

    private static final Logger log = LoggerFactory.getLogger(ContextSnip.class);

    private ContextSnip() {}

    public record SnipRemoval(int originalIndex, String messageType, String reason) {}

    public record SnipResult(List<Message> messages, List<SnipRemoval> removals) {}

    public static List<Message> snip(List<Message> messages) {
        return snipWithReport(messages).messages();
    }

    public static SnipResult snipWithReport(List<Message> messages) {
        if (messages == null || messages.isEmpty()
                || messages.size() <= CompactConfig.SNIP_MIN_MESSAGE_COUNT) {
            return new SnipResult(messages == null ? List.of() : messages, List.of());
        }

        int protectedFrom = Math.max(0,
                messages.size() - CompactConfig.SNIP_PROTECT_RECENT_MESSAGES);
        List<SnipRemoval> removals = new ArrayList<>();
        String previousUserText = null;

        for (int i = 0; i < protectedFrom; i++) {
            Message message = messages.get(i);
            if (ToolCallIntegrity.isToolCall(message)
                    || ToolCallIntegrity.isToolResponse(message)) {
                previousUserText = null;
                continue;
            }

            if (message.getMessageType() == MessageType.USER) {
                String text = message.getText();
                if (text != null && text.equals(previousUserText)) {
                    removals.add(new SnipRemoval(
                            i, message.getMessageType().name(), "duplicate consecutive user message"));
                    continue;
                }
                previousUserText = text;
                continue;
            }
            previousUserText = null;

            if (!(message instanceof AssistantMessage assistantMessage)) {
                continue;
            }
            String reason = classifyAssistant(messages, i, assistantMessage);
            if (reason != null) {
                removals.add(new SnipRemoval(
                        i, message.getMessageType().name(), reason));
            }
        }

        if (removals.isEmpty()) {
            return new SnipResult(messages, List.of());
        }

        List<Message> result = new ArrayList<>(messages.size() - removals.size() + 1);
        int firstRemoval = removals.get(0).originalIndex();
        for (int i = 0; i < messages.size(); i++) {
            if (i == firstRemoval) {
                result.add(new SystemMessage(buildMarker(removals)));
            }
            if (!isRemoved(removals, i)) {
                result.add(messages.get(i));
            }
        }

        logRemovals(removals);
        return new SnipResult(List.copyOf(result), List.copyOf(removals));
    }

    private static String classifyAssistant(
            List<Message> messages, int index, AssistantMessage message) {
        String text = message.getText();
        if (text == null || text.isBlank()) {
            return null;
        }
        if (isTransientError(text) && hasLaterSubstantiveMessage(messages, index)) {
            return "transient error";
        }
        if (isLowValueFiller(text) && hasLaterSubstantiveMessage(messages, index)) {
            return "low-value filler";
        }
        return null;
    }

    private static boolean hasLaterSubstantiveMessage(List<Message> messages, int index) {
        for (int i = index + 1; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (ToolCallIntegrity.isToolResponse(message)) {
                return true;
            }
            if (message instanceof AssistantMessage assistantMessage) {
                String text = assistantMessage.getText();
                if (text != null && !text.isBlank() && !isTransientError(text)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isTransientError(String text) {
        String lower = text.toLowerCase();
        for (String pattern : CompactConfig.SNIP_ERROR_PATTERNS) {
            if (lower.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLowValueFiller(String text) {
        if (text.length() > CompactConfig.SNIP_SHORT_MSG_MAX_CHARS
                || containsHighValueSignal(text)) {
            return false;
        }
        String lower = text.toLowerCase().trim();
        return lower.startsWith("let me")
                || lower.startsWith("i'll")
                || lower.startsWith("i will")
                || lower.startsWith("thinking...")
                || lower.equals("one moment.")
                || lower.equals("sure.")
                || lower.equals("ok");
    }

    private static boolean containsHighValueSignal(String text) {
        String lower = text.toLowerCase();
        return text.chars().anyMatch(Character::isDigit)
                || text.contains("/")
                || text.contains("\\")
                || lower.contains("http")
                || lower.contains("approve")
                || lower.contains("approval")
                || lower.contains("todo")
                || text.contains("?")
                || text.contains("？");
    }

    private static boolean isRemoved(List<SnipRemoval> removals, int index) {
        return removals.stream().anyMatch(removal -> removal.originalIndex() == index);
    }

    private static String buildMarker(List<SnipRemoval> removals) {
        Map<String, Long> counts = removals.stream()
                .collect(Collectors.groupingBy(SnipRemoval::reason, Collectors.counting()));
        String details = counts.entrySet().stream()
                .map(entry -> entry.getValue() + " " + entry.getKey())
                .collect(Collectors.joining(", "));
        return "[ContextSnip: removed " + removals.size() + " message(s): " + details + "]";
    }

    private static void logRemovals(List<SnipRemoval> removals) {
        log.info("ContextSnip removal report: {}", removals);
    }
}
