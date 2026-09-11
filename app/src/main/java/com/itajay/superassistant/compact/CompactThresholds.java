package com.itajay.superassistant.compact;

import com.itajay.superassistant.config.CompactProperties;
import org.springframework.stereotype.Component;

/**
 * Dynamically derived compaction thresholds.
 *
 * <h2>Formula</h2>
 * <pre>
 *   usableBudget = modelContextWindowTokens
 *                − outputReserveTokens          // space for the model's answer
 *                − systemPromptReserveTokens    // system prompt + tools + injected user context
 *
 *   warningTokens  = floor(usableBudget × warningRatio)   // default 0.70 → L1-L3
 *   criticalTokens = floor(usableBudget × criticalRatio)  // default 0.90 → full LLM compaction
 * </pre>
 *
 * <p>The reserves guarantee that even at the critical threshold there is still
 * room for the system prompt, tool definitions, injected memory and the model's
 * output. The 10% gap between {@code warningRatio} and {@code criticalRatio}
 * absorbs L1-L3 gains, and the 10% gap between {@code criticalRatio} and 1.0 is
 * a safety margin for the compaction artifacts themselves (summary + recovered
 * attachments) produced after the trigger fires.</p>
 */
@Component
public class CompactThresholds {

    private final int warningTokens;
    private final int criticalTokens;

    public CompactThresholds(CompactProperties properties) {
        double warningRatio = clampRatio(properties.getWarningRatio(), "warningRatio");
        double criticalRatio = clampRatio(properties.getCriticalRatio(), "criticalRatio");
        if (warningRatio >= criticalRatio) {
            // Keep the pipeline sane: warning must fire before critical.
            warningRatio = 0.70;
            criticalRatio = 0.90;
        }

        long usableBudget = (long) properties.getModelContextWindowTokens()
                - properties.getOutputReserveTokens()
                - properties.getSystemPromptReserveTokens();
        if (usableBudget <= 0) {
            // Misconfigured window: fall back to conservative absolute numbers.
            this.warningTokens = 60_000;
            this.criticalTokens = 80_000;
            return;
        }

        this.warningTokens = (int) Math.floor(usableBudget * warningRatio);
        this.criticalTokens = (int) Math.floor(usableBudget * criticalRatio);
    }

    /** Token count at which L1-L3 compaction starts. */
    public int warningTokens() {
        return warningTokens;
    }

    /** Token count at which full LLM compaction is triggered. */
    public int criticalTokens() {
        return criticalTokens;
    }

    private static double clampRatio(double ratio, String name) {
        if (!Double.isFinite(ratio) || ratio <= 0.0 || ratio > 1.0) {
            return name.equals("criticalRatio") ? 0.90 : 0.70;
        }
        return ratio;
    }
}
