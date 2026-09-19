package com.itajay.superassistant.app;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.itajay.superassistant.plan.PlanModeContext;
import com.itajay.superassistant.progress.ProgressChannelRegistry;
import com.itajay.superassistant.security.ApprovalDecision;
import com.itajay.superassistant.security.HITLHelper;
import com.itajay.superassistant.security.PendingInterruptionStore;
import com.itajay.superassistant.service.ChatMessagePersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * The chat endpoints. Both POSTs return {@code text/event-stream}: the response body
 * <em>is</em> the run. See {@link ChatStreamingService} for the event protocol
 * ({@code progress}/{@code delta}/{@code answer}/{@code interruption}/{@code error}/
 * {@code done}).
 *
 * <p>{@code GET /chat/{threadId}/stream} remains as a debug-only listener for the
 * progress events; the client-facing stream is the POST response itself.</p>
 */
@RestController
@RequestMapping("/api")
public class SuperAssistant {

    private static final Logger log = LoggerFactory.getLogger(SuperAssistant.class);

    private final ChatStreamingService chatStreamingService;
    private final PendingInterruptionStore pendingInterruptionStore;
    private final ChatMessagePersistenceService messagePersistenceService;
    private final ProgressChannelRegistry progressChannels;

    public SuperAssistant(ChatStreamingService chatStreamingService,
                          PendingInterruptionStore pendingInterruptionStore,
                          ChatMessagePersistenceService messagePersistenceService,
                          ProgressChannelRegistry progressChannels) {
        this.chatStreamingService = chatStreamingService;
        this.pendingInterruptionStore = pendingInterruptionStore;
        this.messagePersistenceService = messagePersistenceService;
        this.progressChannels = progressChannels;
    }

    /** Debug listener for a thread's progress events. Not used by the chat client. */
    @GetMapping(value = "/chat/{threadId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String threadId) {
        return progressChannels.register(threadId);
    }

    /**
     * Starts one agent turn. Returns immediately; the run's output — tool progress,
     * incremental model text, and one terminal event — streams back as the response
     * body until {@code done}.
     */
    @PostMapping(value = "/chat/{threadId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@PathVariable String threadId,
                           @RequestBody ChatRequest request) {
        log.info("Chat request [thread={}]: {}", threadId, request.message());

        String reqMode = request.mode() != null ? request.mode() : "Default";
        boolean planEnabled = "PlanMode".equalsIgnoreCase(reqMode);
        PlanModeContext.setEnabled(threadId, planEnabled);

        try {
            messagePersistenceService.saveUserMessage(threadId, request.message());
            RunnableConfig config = buildConfig(threadId);
            config.context().put("threadId", threadId);
            config.context().put("planEnabled", String.valueOf(planEnabled));
            return chatStreamingService.start(threadId, request.message(), config, planEnabled);
        } catch (Exception e) {
            log.error("Chat request failed before the run started [thread={}]", threadId, e);
            pendingInterruptionStore.remove(threadId);
            return chatStreamingService.rejected(threadId, e.getMessage());
        }
    }

    /**
     * Resumes a run that interrupted for human approval. Same stream semantics as
     * {@link #chat}: the resumed run streams until its own terminal event. An
     * approval can interrupt again (a second risky tool call), in which case the new
     * pending interruption replaces the old one and the client can approve again.
     */
    @PostMapping(value = "/chat/{threadId}/approve", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter approve(@PathVariable String threadId,
                              @RequestBody ApproveRequest request) {
        log.info("Approve request [thread={}]: {} decision(s)", threadId,
                request.decisions() != null ? request.decisions().size() : 0);

        PendingInterruptionStore.PendingInterruption pending = pendingInterruptionStore.get(threadId);
        if (pending == null) {
            return chatStreamingService.rejected(threadId, "No pending interruption, threadId=" + threadId);
        }

        boolean planEnabled = PlanModeContext.isEnabled(threadId);

        try {
            InterruptionMetadata resolved = HITLHelper.approveOneByOne(
                    pending.metadata(), request.decisions());

            RunnableConfig resumeConfig = RunnableConfig.builder(pending.config())
                    .addHumanFeedback(resolved)
                    .build();

            return chatStreamingService.start(threadId, pending.inputMessage(), resumeConfig, planEnabled);
        } catch (Exception e) {
            log.error("Approve request failed before the run resumed [thread={}]", threadId, e);
            pendingInterruptionStore.remove(threadId);
            return chatStreamingService.rejected(threadId, e.getMessage());
        }
    }

    private RunnableConfig buildConfig(String threadId) {
        PendingInterruptionStore.PendingInterruption pending = pendingInterruptionStore.get(threadId);
        if (pending != null) {
            return RunnableConfig.builder(pending.config())
                    .addMetadata("threadId", threadId)
                    .build();
        }
        return RunnableConfig.builder()
                .threadId(threadId)
                .addMetadata("threadId", threadId)
                .build();
    }

    public record ChatRequest(String message, String mode) {
        public ChatRequest {
            if (mode == null || mode.isBlank()) mode = "Default";
        }
    }

    public record ApproveRequest(List<ApprovalDecision> decisions) {}
}
