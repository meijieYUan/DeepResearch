package com.itajay.superassistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime properties for the context-compaction pipeline.
 *
 * <p>Thresholds are no longer hard-coded: they are derived from the model
 * context window, minus reserved space for the output window and for the
 * system prompt / tool definitions / injected user context. See
 * {@code CompactThresholds} for the formula.</p>
 */
@ConfigurationProperties(prefix = "agent.compact")
public class CompactProperties {

    // ── Context-window budget ──

    /** Total context window of the deployed model (tokens). DeepSeek chat: 128K. */
    private int modelContextWindowTokens = 128_000;

    /** Reserved space for the model's max output tokens. */
    private int outputReserveTokens = 8_000;

    /**
     * Reserved space for system prompt + tool definitions + injected user
     * context / memory / MCP instructions that are not part of "messages".
     */
    private int systemPromptReserveTokens = 12_000;

    /** Ratio of the usable budget at which L1-L3 compaction starts. */
    private double warningRatio = 0.70;

    /** Ratio of the usable budget at which full LLM compaction is triggered. */
    private double criticalRatio = 0.90;

    // ── Compaction cadence ──

    /** Minimum agent calls between two full compactions per thread. */
    private int cooldownCalls = 5;

    // ── Storage housekeeping ──

    /** Number of full-compaction snapshots kept per thread. */
    private int snapshotKeep = 5;

    // ── Getters / setters ──

    public int getModelContextWindowTokens() {
        return modelContextWindowTokens;
    }

    public void setModelContextWindowTokens(int modelContextWindowTokens) {
        this.modelContextWindowTokens = modelContextWindowTokens;
    }

    public int getOutputReserveTokens() {
        return outputReserveTokens;
    }

    public void setOutputReserveTokens(int outputReserveTokens) {
        this.outputReserveTokens = outputReserveTokens;
    }

    public int getSystemPromptReserveTokens() {
        return systemPromptReserveTokens;
    }

    public void setSystemPromptReserveTokens(int systemPromptReserveTokens) {
        this.systemPromptReserveTokens = systemPromptReserveTokens;
    }

    public double getWarningRatio() {
        return warningRatio;
    }

    public void setWarningRatio(double warningRatio) {
        this.warningRatio = warningRatio;
    }

    public double getCriticalRatio() {
        return criticalRatio;
    }

    public void setCriticalRatio(double criticalRatio) {
        this.criticalRatio = criticalRatio;
    }

    public int getCooldownCalls() {
        return cooldownCalls;
    }

    public void setCooldownCalls(int cooldownCalls) {
        this.cooldownCalls = cooldownCalls;
    }

    public int getSnapshotKeep() {
        return snapshotKeep;
    }

    public void setSnapshotKeep(int snapshotKeep) {
        this.snapshotKeep = snapshotKeep;
    }
}