package com.itajay.superassistant.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The failure ledger the workflow reads to decide whether to pause.
 *
 * <p>Kept deliberately small: the interesting behaviour is that a drained entry is
 * not reported twice, and that a later success cancels an earlier failure — those
 * two rules are what keep a resumed run from pausing on papers it now has.</p>
 */
class DownloadFailureRegistryTest {

    private final DownloadFailureRegistry registry = new DownloadFailureRegistry();

    @Test
    void recordsAndDrainsFailuresForOneRun() {
        registry.record("t1", "topic-a", "Paper-One", "https://x/1.pdf", "HTTP 403");
        registry.record("t1", "topic-a", "Paper-Two", "https://x/2.pdf", "timed out");

        DownloadFailureRegistry.Snapshot snapshot = registry.drain("t1", "topic-a");

        assertThat(snapshot.items()).extracting(DownloadFailureRegistry.Failure::shortName)
                .containsExactlyInAnyOrder("Paper-One", "Paper-Two");
        // Draining is one-shot: a retried workflow must not re-report the same papers.
        assertThat(registry.drain("t1", "topic-a").isEmpty()).isTrue();
    }

    @Test
    void aLaterSuccessCancelsTheFailure() {
        registry.record("t1", "topic-a", "Paper-One", "https://x/1.pdf", "HTTP 403");
        registry.resolved("t1", "topic-a", "Paper-One", PaperIdentity.fromReference("https://x/1.pdf"));

        assertThat(registry.drain("t1", "topic-a").isEmpty()).isTrue();
    }

    private static final PaperIdentity ATTENTION =
            new PaperIdentity("1706.03762", null, "Attention Is All You Need", null);
    private static final PaperIdentity RESNET =
            new PaperIdentity("1512.03385", null, "Deep Residual Learning for Image Recognition", null);

    /** Otherwise the run goes on without a paper it never got. */
    @Test
    void anotherPaperSucceedingUnderTheSameNameCancelsNothing() {
        registry.record("t1", "topic-a", "Survey", "https://x/1.pdf", "HTTP 403", RESNET);
        registry.resolved("t1", "topic-a", "Survey", ATTENTION);

        assertThat(registry.drain("t1", "topic-a").items()).hasSize(1);
    }

    @Test
    void theSamePaperSucceedingUnderAnotherNameCancelsTheFailure() {
        registry.record("t1", "topic-a", "Survey", "https://x/1.pdf", "HTTP 403", RESNET);
        registry.resolved("t1", "topic-a", "ResNet", new PaperIdentity("1512.03385", null, null, null));

        assertThat(registry.drain("t1", "topic-a").isEmpty()).isTrue();
    }

    @Test
    void twoPapersFailingUnderOneNameAreBothKept() {
        registry.record("t1", "topic-a", "Survey", null, "HTTP 403", RESNET);
        registry.record("t1", "topic-a", "Survey", null, "HTTP 403", ATTENTION);

        assertThat(registry.drain("t1", "topic-a").items())
                .extracting(DownloadFailureRegistry.Failure::fileName)
                .containsExactlyInAnyOrder("Survey-1512.03385.pdf", "Survey-1706.03762.pdf");
    }

    /** A retry with corrected identifiers is the same request, not a second missing paper. */
    @Test
    void aCorrectedRetryReplacesTheEarlierFailure() {
        registry.record("t1", "topic-a", "ResNet", null, "no DOI",
                new PaperIdentity(null, null, "ResNet: Deep Residual Learning for Image Recognition", null));
        registry.record("t1", "topic-a", "ResNet", null, "HTTP 403", RESNET);

        assertThat(registry.drain("t1", "topic-a").items()).hasSize(1);
    }

    @Test
    void failuresAreScopedToThreadAndTopic() {
        registry.record("t1", "topic-a", "Paper-One", null, "HTTP 403");
        registry.record("t2", "topic-a", "Paper-Two", null, "HTTP 403");
        registry.record("t1", "topic-b", "Paper-Three", null, "HTTP 403");

        assertThat(registry.drain("t1", "topic-a").items()).hasSize(1);
        assertThat(registry.peek("t2", "topic-a").items()).hasSize(1);
        assertThat(registry.peek("t1", "topic-b").items()).hasSize(1);
    }

    @Test
    void clearingAThreadDropsItsEntries() {
        registry.record("t1", "topic-a", "Paper-One", null, "HTTP 403");
        registry.clear("t1");

        assertThat(registry.peek("t1", "topic-a").items()).isEmpty();
    }

    @Test
    void repeatingAFailureKeepsOneEntry() {
        registry.record("t1", "topic-a", "Paper-One", null, "first");
        registry.record("t1", "topic-a", "Paper-One", null, "second");

        List<DownloadFailureRegistry.Failure> items = registry.drain("t1", "topic-a").items();

        assertThat(items).hasSize(1);
        assertThat(items.get(0).reason()).isEqualTo("second");
    }
}
