package com.itajay.superassistant.app;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itajay.superassistant.plan.PlanModeContext;
import com.itajay.superassistant.progress.ProgressChannelRegistry;
import com.itajay.superassistant.security.ApprovalDecision;
import com.itajay.superassistant.security.HITLHelper;
import com.itajay.superassistant.security.PendingInterruptionStore;
import com.itajay.superassistant.service.ChatMessagePersistenceService;
import com.itajay.superassistant.trace.TraceContext;
import com.itajay.superassistant.trace.TraceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    /** Upper bound for one user message; anything longer is a client error, not a run. */
    private static final int MAX_MESSAGE_CHARS = 20_000;

    private final ChatStreamingService chatStreamingService;
    private final PendingInterruptionStore pendingInterruptionStore;
    private final ChatMessagePersistenceService messagePersistenceService;
    private final ProgressChannelRegistry progressChannels;
    private final TraceService traceService;

    public SuperAssistant(ChatStreamingService chatStreamingService,
                          PendingInterruptionStore pendingInterruptionStore,
                          ChatMessagePersistenceService messagePersistenceService,
                          ProgressChannelRegistry progressChannels,
                          TraceService traceService) {
        this.chatStreamingService = chatStreamingService;
        this.pendingInterruptionStore = pendingInterruptionStore;
        this.messagePersistenceService = messagePersistenceService;
        this.progressChannels = progressChannels;
        this.traceService = traceService;
    }

    /** Debug listener for a thread's progress events. Not used by the chat client. */
    @GetMapping(value = "/chat/{threadId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String threadId) {
        String threadError = validateThreadId(threadId);
        if (threadError != null) {
            return chatStreamingService.rejected(threadId, threadError);
        }
        return progressChannels.register(threadId);
    }

    /**
     * Cancels the run currently executing on a thread. Returns
     * {@code {"cancelled": false}} when nothing is running.
     */
    @PostMapping("/chat/{threadId}/cancel")
    public Map<String, Object> cancel(@PathVariable String threadId) {
        String threadError = validateThreadId(threadId);
        if (threadError != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, threadError);
        }
        return Map.of("cancelled", chatStreamingService.cancel(threadId));
    }

    /**
     * Last run's execution trace for a thread: the same events the live stream
     * pushes as {@code trace} frames, served from the in-memory ring or, after a
     * restart, from the newest JSONL file under {@code .trace/}.
     */
    @GetMapping("/chat/{threadId}/trace")
    public TraceService.TraceSnapshot trace(@PathVariable String threadId) {
        String threadError = validateThreadId(threadId);
        if (threadError != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, threadError);
        }
        return traceService.recent(threadId);
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

        String threadError = validateThreadId(threadId);
        if (threadError != null) {
            return chatStreamingService.rejected(threadId, threadError);
        }
        if (request.message() == null || request.message().isBlank()) {
            return chatStreamingService.rejected(threadId, "message 不能为空");
        }
        if (request.message().length() > MAX_MESSAGE_CHARS) {
            return chatStreamingService.rejected(threadId,
                    "message 过长（上限 " + MAX_MESSAGE_CHARS + " 字符）");
        }
        // A pending approval owns this thread's run state. Letting a new message ride
        // on the interrupted config (the old buildConfig behavior) mixed two runs'
        // semantics into one; make the user resolve the approval first.
        if (pendingInterruptionStore.get(threadId) != null) {
            return chatStreamingService.rejected(threadId,
                    "该会话有待审批的高危操作，请先同意或拒绝后再发送新消息");
        }

        String reqMode = request.mode() != null ? request.mode() : "Default";
        boolean planEnabled = "PlanMode".equalsIgnoreCase(reqMode);

        try {
            RunnableConfig config = buildConfig(threadId);
            config.context().put("threadId", threadId);
            config.context().put("planEnabled", String.valueOf(planEnabled));
            // Both side effects run only once the run is actually accepted. Setting
            // the plan-mode flag before start() let a rejected second message (thread
            // already running) flip the mode of the run that was still in flight.
            return chatStreamingService.start(threadId, request.message(), config, planEnabled,
                    () -> {
                        PlanModeContext.setEnabled(threadId, planEnabled);
                        messagePersistenceService.saveUserMessage(threadId, request.message());
                    },
                    TraceContext.currentTraceId());
        } catch (Exception e) {
            log.error("Chat request failed before the run started [thread={}]", threadId, e);
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

        String threadError = validateThreadId(threadId);
        if (threadError != null) {
            return chatStreamingService.rejected(threadId, threadError);
        }

        PendingInterruptionStore.PendingInterruption pending = pendingInterruptionStore.get(threadId);
        if (pending == null) {
            return chatStreamingService.rejected(threadId, "No pending interruption, threadId=" + threadId);
        }

        // Validate before touching the pending state: a malformed request must not
        // discard the interruption (the old flow NPE'd on null decisions and the catch
        // then silently dropped the pending approval, forcing a full re-run). The
        // expected toolIds come from the pending metadata: a decision for an unknown
        // tool, or a missing one, would otherwise resume the run with null feedback
        // and cause the framework to interrupt again with all decisions discarded.
        String validationError = validateDecisions(request.decisions(), pending.metadata());
        if (validationError != null) {
            return chatStreamingService.rejected(threadId, validationError);
        }

        boolean planEnabled = PlanModeContext.isEnabled(threadId);

        try {
            InterruptionMetadata resolved = HITLHelper.approveOneByOne(
                    pending.metadata(), request.decisions());

            RunnableConfig resumeConfig = RunnableConfig.builder(pending.config())
                    .addHumanFeedback(resolved)
                    .build();

            // Reuse the interrupted run's trace id so a resumed run stays one
            // logical trace in the logs and in the UI.
            Object storedTraceId = pending.config().context().get(TraceContext.TRACE_ID);
            String traceId = storedTraceId == null
                    ? TraceContext.currentTraceId() : String.valueOf(storedTraceId);
            return chatStreamingService.start(threadId, pending.inputMessage(), resumeConfig,
                    planEnabled, null, traceId);
        } catch (Exception e) {
            // Keep the pending interruption: the approval state is still valid and the
            // client can retry. Dropping it here used to force a full task re-run.
            log.error("Approve request failed before the run resumed [thread={}]", threadId, e);
            return chatStreamingService.rejected(threadId, e.getMessage());
        }
    }

    /** Returns an error message for the client, or {@code null} when the decisions are usable. */
    private static String validateDecisions(List<ApprovalDecision> decisions,
                                            InterruptionMetadata metadata) {
        if (decisions == null || decisions.isEmpty()) {
            return "decisions 不能为空：请对每个待审批工具提交 APPROVED / REJECTED / EDITED 决策";
        }
        Set<String> expected = metadata == null ? Set.of() : metadata.toolFeedbacks().stream()
                .map(InterruptionMetadata.ToolFeedback::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> seen = new HashSet<>();
        for (ApprovalDecision decision : decisions) {
            if (decision == null) {
                return "decisions 含空条目";
            }
            if (decision.toolId() == null || decision.toolId().isBlank()) {
                return "每条决策必须携带 toolId";
            }
            if (!expected.isEmpty() && !expected.contains(decision.toolId())) {
                return "决策引用了不属于本次审批的 toolId：" + decision.toolId();
            }
            if (!seen.add(decision.toolId())) {
                return "同一 toolId 出现重复决策：" + decision.toolId();
            }
            if (decision.result() == null) {
                return "每条决策必须携带 result（APPROVED / REJECTED / EDITED）";
            }
            if (decision.result() == InterruptionMetadata.ToolFeedback.FeedbackResult.EDITED) {
                if (decision.editedArguments() == null || decision.editedArguments().isBlank()) {
                    return "EDITED 决策必须提供 editedArguments";
                }
                try {
                    JSON_MAPPER.readTree(decision.editedArguments());
                } catch (Exception e) {
                    return "editedArguments 不是合法 JSON: " + e.getMessage();
                }
            }
        }
        for (String toolId : expected) {
            if (!seen.contains(toolId)) {
                return "缺少待审批工具的决策：" + toolId;
            }
        }
        return null;
    }

    /**
     * Rejects thread ids that cannot be valid conversation keys. Besides preventing
     * blank keys (which used to leak a run slot), this keeps path-like and control
     * characters out of checkpoint keys and the plan-file name.
     */
    private static String validateThreadId(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return "threadId 不能为空";
        }
        if (threadId.length() > 100) {
            return "threadId 过长（上限 100 字符）";
        }
        for (int i = 0; i < threadId.length(); i++) {
            char c = threadId.charAt(i);
            if (c < 0x20 || c == 0x7f || "/\\:*?\"<>|".indexOf(c) >= 0) {
                return "threadId 含非法字符";
            }
        }
        return null;
    }

    /**
     * Builds a fresh config for a new run. Pending interruptions are handled by
     * {@link #approve} (which resumes from the stored config); {@link #chat} refuses
     * new messages while an approval is pending, so there is no interrupted-config
     * path here anymore.
     */
    private RunnableConfig buildConfig(String threadId) {
        var builder = RunnableConfig.builder()
                .threadId(threadId)
                .addMetadata("threadId", threadId);
        String traceId = TraceContext.currentTraceId();
        if (traceId != null) {
            // AgentLlmNode exposes RunnableConfig.metadata to ModelRequest.context;
            // putting the id here is what lets model tracing survive reactor threads.
            builder.addMetadata(TraceContext.TRACE_ID, traceId);
        }
        return builder.build();
    }

    public record ChatRequest(String message, String mode) {
        public ChatRequest {
            if (mode == null || mode.isBlank()) mode = "Default";
        }
    }

    public record ApproveRequest(List<ApprovalDecision> decisions) {}
}
