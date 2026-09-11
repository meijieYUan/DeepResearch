package com.itajay.superassistant.compact;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/**
 * Local BPE token estimator.
 *
 * O200K is not the DeepSeek tokenizer, but it is substantially closer than the
 * previous UTF-8 byte heuristic for mixed Chinese, English, and source code.
 * A small safety margin and per-message overhead keep the estimate conservative.
 */
final class BpeTokenEstimator implements TokenEstimator {

    private static final Logger log = LoggerFactory.getLogger(BpeTokenEstimator.class);
    private static final double SAFETY_MARGIN = 1.10;
    private static final int MESSAGE_OVERHEAD_TOKENS = 4;

    private final Encoding encoding;
    private final HeuristicTokenEstimator fallback = new HeuristicTokenEstimator();

    BpeTokenEstimator() {
        EncodingRegistry registry = Encodings.newLazyEncodingRegistry();
        this.encoding = registry.getEncoding(EncodingType.O200K_BASE);
    }

    @Override
    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        try {
            return encoding.countTokens(text);
        } catch (Exception e) {
            log.warn("BPE token counting failed; using heuristic fallback", e);
            return fallback.estimate(text);
        }
    }

    @Override
    public int estimate(Message message) {
        if (message == null) {
            return 0;
        }

        int tokens = estimate(message.getText());
        if (message instanceof AssistantMessage assistantMessage) {
            for (AssistantMessage.ToolCall call : assistantMessage.getToolCalls()) {
                tokens += estimate(call.name()) + estimate(call.arguments());
            }
        } else if (message instanceof ToolResponseMessage responseMessage) {
            for (ToolResponseMessage.ToolResponse response : responseMessage.getResponses()) {
                tokens += estimate(response.name()) + estimate(response.responseData());
            }
        }
        return addMessageOverhead(tokens);
    }

    @Override
    public int estimate(Iterable<?> messages) {
        int total = 0;
        for (Object item : messages) {
            if (item instanceof Message message) {
                total += estimate(message);
            }
        }
        return total;
    }

    private static int addMessageOverhead(int tokens) {
        return (int) Math.ceil(tokens * SAFETY_MARGIN) + MESSAGE_OVERHEAD_TOKENS;
    }
}
