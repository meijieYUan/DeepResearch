package com.itajay.superassistant.tool;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import com.itajay.superassistant.trace.TraceContext;
import com.itajay.superassistant.trace.TraceModelInterceptor;
import com.itajay.superassistant.trace.TraceToolInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Dynamic sub-agent creation tool.
 * Allows the main agent to spawn a new ReactAgent on-the-fly to handle a specific subtask.
 *
 * <p>The child runs with a {@code RunnableConfig} carrying the current thread and trace
 * ids, a model-call limit, and the trace interceptors. It deliberately has no tools:
 * an arbitrary model-written instruction must not gain file/terminal access without
 * the main agent's HITL gates. The result is read from {@link AssistantMessage#getText()}
 * rather than {@code state.value("output")} — the framework only writes that key when
 * {@code outputKey} is configured, which this dynamic agent does not do.</p>
 */
@Component
public class CreateAgentTool {

    private static final Logger log = LoggerFactory.getLogger(CreateAgentTool.class);
    private static final int MAX_INSTRUCTION_CHARS = 8_000;
    private static final int MAX_TASK_CHARS = 8_000;
    private static final int MAX_MODEL_CALLS = 6;

    private final ChatModel chatModel;
    private final TraceToolInterceptor traceToolInterceptor;
    private final TraceModelInterceptor traceModelInterceptor;

    public CreateAgentTool(ChatModel chatModel,
                           TraceToolInterceptor traceToolInterceptor,
                           TraceModelInterceptor traceModelInterceptor) {
        this.chatModel = chatModel;
        this.traceToolInterceptor = traceToolInterceptor;
        this.traceModelInterceptor = traceModelInterceptor;
    }

    @Tool(description = """
            创建一个子Agent来独立完成指定的子任务。子Agent拥有独立的上下文，会自主执行并返回结果。
            适用于需要将复杂任务拆分为多个独立子任务、或需要特定专业能力来完成子任务的场景。""")
    public String createAgent(
            @ToolParam(description = "子Agent的名称，如 'code-reviewer'、'data-analyzer'") String agentName,
            @ToolParam(description = "子Agent的描述，说明它的能力和用途") String description,
            @ToolParam(description = "子Agent的系统指令，详细说明它应该如何完成子任务，包括步骤、输出格式和约束条件") String instruction,
            @ToolParam(description = "要交给子Agent的具体任务内容") String task,
            ToolContext toolContext) {

        if (instruction == null || instruction.isBlank() || task == null || task.isBlank()) {
            return "Error: instruction and task must not be blank";
        }
        if (instruction.length() > MAX_INSTRUCTION_CHARS || task.length() > MAX_TASK_CHARS) {
            return "Error: instruction/task exceeds the length limit";
        }
        log.info("CreateAgent [name={}]", agentName);

        ReactAgent subAgent = ReactAgent.builder()
                .name(agentName == null || agentName.isBlank() ? "dynamic-agent" : agentName)
                .description(description == null ? "" : description)
                .model(chatModel)
                .instruction(instruction)
                .hooks(ModelCallLimitHook.builder()
                        .runLimit(MAX_MODEL_CALLS)
                        .exitBehavior(ModelCallLimitHook.ExitBehavior.END)
                        .build())
                .interceptors(traceToolInterceptor, traceModelInterceptor)
                .build();

        try {
            AssistantMessage answer = subAgent.call(task, childConfig(toolContext));
            String output = answer == null ? null : answer.getText();
            return "Sub-agent [" + agentName + "] completed:\n\n"
                    + (output == null || output.isBlank() ? "(no output)" : output);
        } catch (Exception e) {
            log.error("CreateAgent [{}] failed", agentName, e);
            return "Sub-agent [" + agentName + "] failed: " + e.getMessage();
        }
    }

    /** Copies only the identities needed for tracing; parent messages/feedback are not inherited. */
    private static RunnableConfig childConfig(ToolContext toolContext) {
        var builder = RunnableConfig.builder();
        String traceId = TraceContext.currentTraceId();
        String runId = null;
        TraceContext.State state = TraceContext.current();
        if (state != null && state.runId() != null) {
            runId = state.runId();
        }

        ToolContextHelper.getConfig(toolContext).ifPresent(parent -> {
            Object parentThread = parent.context().get("threadId");
            if (parentThread != null) {
                builder.threadId(String.valueOf(parentThread));
            }
            Map<String, Object> metadata = parent.metadata().orElse(Map.of());
            Object metadataTrace = metadata.get(TraceContext.TRACE_ID);
            Object metadataRun = metadata.get(TraceContext.RUN_ID);
            if (metadataTrace != null) {
                builder.addMetadata(TraceContext.TRACE_ID, String.valueOf(metadataTrace));
            }
            if (metadataRun != null) {
                builder.addMetadata(TraceContext.RUN_ID, String.valueOf(metadataRun));
            }
            Object contextThread = parent.context().get("threadId");
            if (contextThread != null) {
                builder.addMetadata("threadId", String.valueOf(contextThread));
            }
        });
        if (traceId != null) {
            builder.addMetadata(TraceContext.TRACE_ID, traceId);
        }
        if (runId != null) {
            builder.addMetadata(TraceContext.RUN_ID, runId);
        }
        RunnableConfig config = builder.build();
        if (traceId != null) {
            config.context().put(TraceContext.TRACE_ID, traceId);
        }
        if (runId != null) {
            config.context().put(TraceContext.RUN_ID, runId);
        }
        return config;
    }
}
