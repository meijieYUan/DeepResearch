package com.itajay.superassistant.app;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.itajay.superassistant.plan.PlanContextHolder;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
public class SuperAssistant {

    private static final Logger log = LoggerFactory.getLogger(SuperAssistant.class);

    private final ReactAgent mainAgent;
    private final PendingInterruptionStore pendingInterruptionStore;
    private final ChatMessagePersistenceService messagePersistenceService;
    private final ProgressChannelRegistry progressChannels;

    public SuperAssistant(ReactAgent mainAgent,
                          PendingInterruptionStore pendingInterruptionStore,
                          ChatMessagePersistenceService messagePersistenceService,
                          ProgressChannelRegistry progressChannels) {
        this.mainAgent = mainAgent;
        this.pendingInterruptionStore = pendingInterruptionStore;
        this.messagePersistenceService = messagePersistenceService;
        this.progressChannels = progressChannels;
    }

    /**
     * Progress stream for a conversation thread.
     *
     * <p>A separate connection from {@code POST /chat/{threadId}}, which stays
     * blocking. Keeping the run synchronous is deliberate: the run holds a
     * thread-local plan-mode context and can suspend for human approval, and both
     * assume one thread for the whole run. This endpoint exists purely so a long
     * workflow can report where it is while that request is still in flight.</p>
     *
     * <p>The client opens this before sending the chat request and closes it when
     * the POST returns. Nothing here is required for correctness — if no one is
     * listening, the run proceeds and the POST response is the authoritative
     * result.</p>
     */
    @GetMapping(value = "/chat/{threadId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String threadId) {
        log.debug("Progress stream opened [thread={}]", threadId);
        return progressChannels.register(threadId);
    }

    @PostMapping("/chat/{threadId}")
    public Map<String, Object> chat(@PathVariable String threadId,
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
            PlanContextHolder.setThreadId(threadId);
            Optional<NodeOutput> result = mainAgent.invokeAndGetOutput(request.message(), config);

            if (result.isEmpty()) {
                return errorResponse("Agent returned empty result");
            }

            NodeOutput output = result.get();

            if (output instanceof InterruptionMetadata metadata) {
                pendingInterruptionStore.put(threadId, config, metadata, request.message());
                return interruptionResponse(threadId, metadata, planEnabled);
            }

            Object answer = output.state().value("output").orElse(output.toString());
            pendingInterruptionStore.remove(threadId);
            return answerResponse(threadId, answer, planEnabled);

        } catch (Exception e) {
            log.error("Chat error [thread={}]", threadId, e);
            pendingInterruptionStore.remove(threadId);
            return errorResponse(e.getMessage());
        } finally {
            PlanContextHolder.clear();
            // The run is over, so the progress stream has nothing left to report.
            // The POST response carries the result; the client closes its side too.
            progressChannels.complete(threadId);
        }
    }

    @PostMapping("/chat/{threadId}/approve")
    public Map<String, Object> approve(@PathVariable String threadId,
                                       @RequestBody ApproveRequest request) {
        log.info("Approve request [thread={}]: {} decision(s)", threadId,
                request.decisions() != null ? request.decisions().size() : 0);

        PendingInterruptionStore.PendingInterruption pending = pendingInterruptionStore.get(threadId);
        if (pending == null) {
            return errorResponse("No pending interruption, threadId=" + threadId);
        }

        boolean planEnabled = PlanModeContext.isEnabled(threadId);

        try {
            InterruptionMetadata resolved = HITLHelper.approveOneByOne(
                    pending.metadata(), request.decisions());

            RunnableConfig resumeConfig = RunnableConfig.builder(pending.config())
                    .addHumanFeedback(resolved)
                    .build();

            PlanContextHolder.setThreadId(threadId);
            Optional<NodeOutput> result = mainAgent.invokeAndGetOutput(
                    pending.inputMessage(), resumeConfig);

            pendingInterruptionStore.remove(threadId);

            if (result.isEmpty()) {
                return errorResponse("Agent returned empty result after resume");
            }

            NodeOutput output = result.get();

            if (output instanceof InterruptionMetadata metadata) {
                pendingInterruptionStore.put(threadId, resumeConfig, metadata, pending.inputMessage());
                return interruptionResponse(threadId, metadata, planEnabled);
            }

            Object answer = output.state().value("output").orElse(output.toString());
            return answerResponse(threadId, answer, planEnabled);

        } catch (Exception e) {
            log.error("Approve error [thread={}]", threadId, e);
            pendingInterruptionStore.remove(threadId);
            return errorResponse(e.getMessage());
        } finally {
            PlanContextHolder.clear();
            // Only close the stream when the run really finished. An interruption means
            // the run is paused, not done, so the client keeps listening across the
            // approval round-trip.
            if (pendingInterruptionStore.get(threadId) == null) {
                progressChannels.complete(threadId);
            }
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

    private Map<String, Object> interruptionResponse(String threadId, InterruptionMetadata metadata, boolean planEnabled) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "INTERRUPTED");
        response.put("threadId", threadId);
        response.put("message", "High-risk operations require approval");
        response.put("pendingApprovals", HITLHelper.getPendingApprovals(metadata));
        response.put("planEnabled", planEnabled);
        response.put("planActive", PlanModeContext.isActive(threadId));
        return response;
    }

    private Map<String, Object> answerResponse(String threadId, Object answer, boolean planEnabled) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "ANSWER");
        response.put("threadId", threadId);
        response.put("response", answer);
        response.put("planEnabled", planEnabled);
        response.put("planActive", PlanModeContext.isActive(threadId));
        return response;
    }

    private Map<String, Object> errorResponse(String message) {
        return Map.of("type", "ERROR", "message", message == null ? "Unknown error" : message);
    }

    public record ChatRequest(String message, String mode) {
        public ChatRequest {
            if (mode == null || mode.isBlank()) mode = "Default";
        }
    }

    public record ApproveRequest(List<ApprovalDecision> decisions) {}
}
