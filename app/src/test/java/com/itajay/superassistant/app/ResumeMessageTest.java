package com.itajay.superassistant.app;

import com.itajay.superassistant.workflow.ResearchResumeStore.MissingPaper;
import com.itajay.superassistant.workflow.ResearchResumeStore.ResumeTask;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A resumed run knows the missing papers only from this message, so it must tell them apart. */
class ResumeMessageTest {

    private static String messageFor(MissingPaper... missing) {
        return SuperAssistant.resumeMessage(new ResumeTask("thread", "topic", "brief", List.of(missing), Instant.now()));
    }

    @Test
    void twoPapersUnderOneNameStayDistinguishable() {
        String message = messageFor(
                new MissingPaper("Survey", "HTTP 403", "Deep Residual Learning for Image Recognition",
                        "10.1109/cvpr.2016.90", null, null, "Survey-10.1109-cvpr.2016.90.pdf"),
                new MissingPaper("Survey", "HTTP 403", null, null, "1706.03762",
                        "https://arxiv.org/abs/1706.03762", "Survey-1706.03762.pdf"));

        assertThat(message).contains("Deep Residual Learning for Image Recognition", "10.1109/cvpr.2016.90",
                "arXiv 1706.03762", "downloadPaper");
    }

    @Test
    void aPaperKnownOnlyByItsLinkKeepsTheLink() {
        assertThat(messageFor(new MissingPaper("Survey", "HTTP 403", null, null, null,
                "https://example.org/paper", "Survey-1a2b3c4d.pdf")))
                .contains("https://example.org/paper");
    }

    @Test
    void nothingMissingAddsNoInstructionAboutIt() {
        assertThat(messageFor()).doesNotContain("downloadPaper");
    }
}
