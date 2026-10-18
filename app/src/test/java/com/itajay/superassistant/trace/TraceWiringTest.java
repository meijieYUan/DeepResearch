package com.itajay.superassistant.trace;

import com.itajay.superassistant.progress.ProgressChannelRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the constructor selection (the test seam is a second constructor). */
class TraceWiringTest {

    @Test
    void traceServiceIsInstantiatedBySpring() {
        new ApplicationContextRunner()
                .withUserConfiguration(TraceService.class, ProgressChannelRegistry.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(TraceService.class);
                });
    }
}
