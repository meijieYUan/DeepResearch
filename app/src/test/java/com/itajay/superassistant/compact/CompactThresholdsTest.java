package com.itajay.superassistant.compact;

import com.itajay.superassistant.config.CompactProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompactThresholdsTest {

    @Test
    void derivesThresholdsFromWindowMinusReserves() {
        CompactProperties properties = new CompactProperties();
        properties.setModelContextWindowTokens(128_000);
        properties.setOutputReserveTokens(8_000);
        properties.setSystemPromptReserveTokens(12_000);
        properties.setWarningRatio(0.70);
        properties.setCriticalRatio(0.90);

        CompactThresholds thresholds = new CompactThresholds(properties);

        // usableBudget = 128000 - 8000 - 12000 = 108000
        assertThat(thresholds.warningTokens()).isEqualTo(75_600);
        assertThat(thresholds.criticalTokens()).isEqualTo(97_200);
        assertThat(thresholds.warningTokens()).isLessThan(thresholds.criticalTokens());
    }

    @Test
    void keepsWarningBelowCriticalWhenMisconfigured() {
        CompactProperties properties = new CompactProperties();
        properties.setModelContextWindowTokens(64_000);
        properties.setWarningRatio(0.90); // higher than critical
        properties.setCriticalRatio(0.70);

        CompactThresholds thresholds = new CompactThresholds(properties);

        assertThat(thresholds.warningTokens()).isLessThan(thresholds.criticalTokens());
        assertThat(thresholds.warningTokens()).isPositive();
    }

    @Test
    void fallsBackToConservativeNumbersWhenBudgetInvalid() {
        CompactProperties properties = new CompactProperties();
        properties.setModelContextWindowTokens(1_000);  // less than reserves
        properties.setOutputReserveTokens(8_000);

        CompactThresholds thresholds = new CompactThresholds(properties);

        assertThat(thresholds.warningTokens()).isEqualTo(60_000);
        assertThat(thresholds.criticalTokens()).isEqualTo(80_000);
    }

    @Test
    void rejectsInvalidRatios() {
        CompactProperties properties = new CompactProperties();
        properties.setModelContextWindowTokens(128_000);
        properties.setWarningRatio(2.0);   // out of range
        properties.setCriticalRatio(-1.0); // out of range

        CompactThresholds thresholds = new CompactThresholds(properties);

        assertThat(thresholds.warningTokens()).isEqualTo(75_600);
        assertThat(thresholds.criticalTokens()).isEqualTo(97_200);
    }
}
