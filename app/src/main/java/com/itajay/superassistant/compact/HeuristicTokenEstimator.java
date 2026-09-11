package com.itajay.superassistant.compact;

import org.springframework.ai.chat.messages.Message;

/**
 * Conservative fallback when a BPE tokenizer is unavailable.
 */
final class HeuristicTokenEstimator implements TokenEstimator {

    HeuristicTokenEstimator() {}

    @Override
    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int cjk = 0;
        int ascii = 0;
        int otherBytes = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (isCjk(ch)) {
                cjk++;
            } else if (ch < 128) {
                ascii++;
            } else {
                otherBytes += utf8ByteLength(ch);
            }
        }
        return cjk + ceil(ascii, 4) + ceil(otherBytes, 4);
    }

    @Override
    public int estimate(Message message) {
        return estimate(message.getText());
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

    private static boolean isCjk(char ch) {
        return (ch >= 0x3400 && ch <= 0x4DBF)
                || (ch >= 0x4E00 && ch <= 0x9FFF)
                || (ch >= 0xF900 && ch <= 0xFAFF);
    }

    private static int utf8ByteLength(char ch) {
        if (ch <= 0x7F) return 1;
        if (ch <= 0x7FF) return 2;
        return 3;
    }

    private static int ceil(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }
}
