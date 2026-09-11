package com.itajay.superassistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.checkpoint-retention")
public class CheckpointRetentionProperties {

    private int retentionDays = 30;
    private int maxPerThread = 20;
    private int activeGraceHours = 24;

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    public int getMaxPerThread() {
        return maxPerThread;
    }

    public void setMaxPerThread(int maxPerThread) {
        this.maxPerThread = maxPerThread;
    }

    public int getActiveGraceHours() {
        return activeGraceHours;
    }

    public void setActiveGraceHours(int activeGraceHours) {
        this.activeGraceHours = activeGraceHours;
    }
}
