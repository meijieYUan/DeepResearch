package com.itajay.superassistant.compact;

final class TokenBudgets {

    private TokenBudgets() {}

    static String previewWithinTokenBudget(String content, int maxTokens) {
        if (CompactConfig.estimateTokens(content) <= maxTokens) {
            return content;
        }

        int low = 0;
        int high = content.length();
        while (low < high) {
            int mid = (low + high + 1) / 2;
            if (CompactConfig.estimateTokens(content.substring(0, mid)) <= maxTokens) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }

        String preview = content.substring(0, low);
        int lastLineBreak = preview.lastIndexOf('\n');
        if (lastLineBreak > 0) {
            preview = preview.substring(0, lastLineBreak);
        }
        return preview;
    }
}
