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
import com.itajay.superassistant.workflow.ResearchResumeStore;
import com.itajay.superassistant.workspace.WorkspacePaths;
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
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    /** Upper bound for one user message; anything longer is a client error, not a run. */
    private static final int MAX_MESSAGE_CHARS = 20_000;

    private final ChatStreamingService chatStreamingService;
    private final PendingInterruptionStore pendingInterruptionStore;
    private final ChatMessagePersistenceService messagePersistenceService;
    private final ProgressChannelRegistry progressChannels;
    private final ResearchResumeStore researchResumeStore;

    public SuperAssistant(ChatStreamingService chatStreamingService,
                          PendingInterruptionStore pendingInterruptionStore,
                          ChatMessagePersistenceService messagePersistenceService,
                          ProgressChannelRegistry progressChannels,
                          ResearchResumeStore researchResumeStore) {
        this.chatStreamingService = chatStreamingService;
        this.pendingInterruptionStore = pendingInterruptionStore;
        this.messagePersistenceService = messagePersistenceService;
        this.progressChannels = progressChannels;
        this.researchResumeStore = researchResumeStore;
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
        PlanModeContext.setEnabled(threadId, planEnabled);

        try {
            RunnableConfig config = buildConfig(threadId);
            config.context().put("threadId", threadId);
            config.context().put("planEnabled", String.valueOf(planEnabled));
            // Carried in the context rather than prefixed onto the message: the model
            // must not have to re-derive which folder is meant, and a value it can
            // merely restate correctly is one it can also restate wrongly.
            if (request.continueTopic() != null && !request.continueTopic().isBlank()) {
                config.context().put(
                        com.itajay.superassistant.workflow.ResearchWriteReviewWorkflow.CONTINUE_TOPIC_CONTEXT_KEY,
                        request.continueTopic().trim());
            }
            // The user message is persisted only after the run is actually accepted:
            // persisting first (the old order) left a never-processed user message in
            // the history whenever start() rejected because the thread was busy.
            return chatStreamingService.start(threadId, request.message(), config, planEnabled,
                    () -> messagePersistenceService.saveUserMessage(threadId, request.message()));
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

        // Validate before touching the pending state: a malformed request must not
        // discard the interruption (the old flow NPE'd on null decisions and the catch
        // then silently dropped the pending approval, forcing a full re-run).
        String validationError = validateDecisions(request.decisions());
        if (validationError != null) {
            return chatStreamingService.rejected(threadId, validationError);
        }

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
            // Keep the pending interruption: the approval state is still valid and the
            // client can retry. Dropping it here used to force a full task re-run.
            log.error("Approve request failed before the run resumed [thread={}]", threadId, e);
            return chatStreamingService.rejected(threadId, e.getMessage());
        }
    }

    /**
     * Continues a research run that paused because some papers could not be downloaded.
     *
     * <p>The pause is a normal end of the previous run, not an interruption inside a tool
     * call, so there is no saved agent config to resume from. The workflow is therefore
     * re-entered with the same topic and brief: the topic folder, the shared paper library
     * and the existing per-paper analyses all dedup, so the only work actually repeated is
     * for the papers that were missing. If the user has not supplied them, the run pauses
     * again and reports the same list rather than burning through the research stage.
     */
    @PostMapping(value = "/chat/{threadId}/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resume(@PathVariable String threadId) {
        var task = researchResumeStore.get(threadId);
        if (task.isEmpty()) {
            return chatStreamingService.rejected(threadId,
                    "该会话没有等待补充论文的调研任务（可能已完成或已过期）");
        }
        if (pendingInterruptionStore.get(threadId) != null) {
            return chatStreamingService.rejected(threadId,
                    "该会话有待审批的高危操作，请先同意或拒绝后再继续调研");
        }

        ResearchResumeStore.ResumeTask paused = task.get();
        String message = resumeMessage(paused);
        log.info("Resume request [thread={}, topic={}, missing={}]",
                threadId, paused.topic(), paused.missing().size());

        boolean planEnabled = PlanModeContext.isEnabled(threadId);
        try {
            RunnableConfig config = buildConfig(threadId);
            config.context().put("threadId", threadId);
            config.context().put("planEnabled", String.valueOf(planEnabled));
            // Cleared before starting: the run itself re-registers if it has to pause
            // again, so a stale entry cannot make the next failure look resolved.
            researchResumeStore.remove(threadId);
            return chatStreamingService.start(threadId, message, config, planEnabled,
                    () -> messagePersistenceService.saveUserMessage(threadId, message));
        } catch (Exception e) {
            log.error("Resume request failed before the run started [thread={}]", threadId, e);
            return chatStreamingService.rejected(threadId, e.getMessage());
        }
    }

    /**
     * The synthetic user turn that continues the paused investigation.
     *
     * <p>Written as a user message rather than a special command so the resumed run goes
     * through the ordinary plan → tool → workflow path, and the conversation history keeps
     * a readable record of why the run restarted.</p>
     */
    private static String resumeMessage(ResearchResumeStore.ResumeTask paused) {
        StringBuilder sb = new StringBuilder();
        sb.append("继续上次暂停的调研。\n\n");
        sb.append("课题方向必须逐字使用：").append(paused.topic()).append("\n\n");
        sb.append("原始调研要求（不得改写范围）：\n").append(paused.brief()).append("\n\n");
        if (!paused.missing().isEmpty()) {
            sb.append("上次未能下载的论文（已放入本地论文库 "
                            + WorkspacePaths.relative(WorkspacePaths.paperLibraryDir()) + " 的请直接复用）：\n");
            for (ResearchResumeStore.MissingPaper paper : paused.missing()) {
                sb.append("- ").append(paper.shortName()).append('\n');
            }
            sb.append('\n');
        }
        sb.append("""
                重新调用 researchWriteReview 工作流，课题方向参数以上面给出的为准。
                已下载的论文、本地论文库与已有精读结果都会被复用；只需要补齐仍然缺失的论文。
                如果仍有论文拿不到全文，工作流会再次暂停并给出新的清单。
                """);
        return sb.toString();
    }

    /** Returns an error message for the client, or {@code null} when the decisions are usable. */
    private static String validateDecisions(List<ApprovalDecision> decisions) {
        if (decisions == null || decisions.isEmpty()) {
            return "decisions 不能为空：请对每个待审批工具提交 APPROVED / REJECTED / EDITED 决策";
        }
        for (ApprovalDecision decision : decisions) {
            if (decision == null) {
                return "decisions 含空条目";
            }
            if (decision.toolId() == null || decision.toolId().isBlank()) {
                return "每条决策必须携带 toolId";
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
        return null;
    }

    /**
     * Builds a fresh config for a new run. Pending interruptions are handled by
     * {@link #approve} (which resumes from the stored config); {@link #chat} refuses
     * new messages while an approval is pending, so there is no interrupted-config
     * path here anymore.
     */
    private RunnableConfig buildConfig(String threadId) {
        return RunnableConfig.builder()
                .threadId(threadId)
                .addMetadata("threadId", threadId)
                .build();
    }

    public record ChatRequest(String message, String mode, String continueTopic) {
        public ChatRequest {
            if (mode == null || mode.isBlank()) mode = "Default";
        }
    }

    public record ApproveRequest(List<ApprovalDecision> decisions) {}
}
