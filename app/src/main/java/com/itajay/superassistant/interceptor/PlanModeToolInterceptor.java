package com.itajay.superassistant.interceptor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.itajay.superassistant.plan.PlanContextHolder;
import com.itajay.superassistant.plan.PlanModeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dynamically filters available tools based on the current plan mode state.
 *
 * Three-tier tool visibility:
 * 1. planEnabled==false (user toggle OFF):
 *    enterPlanMode / exitPlanMode are HIDDEN — LLM cannot plan.
 * 2. planEnabled==true, planActive==false (toggle ON, not yet planning):
 *    ALL tools available, including PlanTool.
 * 3. planActive==true (LLM has entered plan mode):
 *    ONLY safe/read-only tools + PlanTool. Dangerous tools are HIDDEN.
 */
@Component
public class PlanModeToolInterceptor extends ModelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(PlanModeToolInterceptor.class);

    /** PlanTool methods — hidden when plan mode is not enabled. */
    private static final Set<String> PLAN_TOOLS = Set.of("enterPlanMode", "exitPlanMode");

    /** Dangerous tools — hidden when plan mode is active. */
    private static final Set<String> DANGEROUS_TOOLS = Set.of(
            "writeFile", "deleteFile", "executeCommand",
            "sendEmail", "sendEmailBatch"
    );

    @Override
    public String getName() {
        return "plan_mode_tool_filter";
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        // The run config carries the threadId in its context, which reaches every
        // model call regardless of which thread the streaming pipeline happens to
        // execute on. PlanContextHolder is a same-thread fallback for runs started
        // the old blocking way.
        String threadId = contextThreadId(request);
        if (threadId == null) {
            threadId = PlanContextHolder.getThreadId();
        }
        if (threadId == null) {
            return handler.call(request);
        }

        boolean enabled = PlanModeContext.isEnabled(threadId);
        boolean active = PlanModeContext.isActive(threadId);

        List<String> tools = request.getTools();
        List<String> filtered;

        if (active) {
            // Tier 3: plan mode active → keep only safe tools
            filtered = tools.stream()
                    .filter(t -> !DANGEROUS_TOOLS.contains(t))
                    .toList();
            log.debug("PlanMode active [thread={}]: {} tools → {} safe", threadId, tools.size(), filtered.size());
        } else if (!enabled) {
            // Tier 1: plan mode disabled → hide PlanTool
            filtered = tools.stream()
                    .filter(t -> !PLAN_TOOLS.contains(t))
                    .toList();
            log.debug("PlanMode disabled [thread={}]: {} tools → {} (PlanTool hidden)", threadId, tools.size(), filtered.size());
        } else {
            // Tier 2: plan mode enabled, not yet active → full access
            return handler.call(request);
        }

        if (filtered.size() == tools.size()) {
            return handler.call(request);
        }

        ModelRequest safeRequest = ModelRequest.builder(request)
                .tools(filtered)
                .build();
        return handler.call(safeRequest);
    }

    private static String contextThreadId(ModelRequest request) {
        Map<String, Object> context = request.getContext();
        Object value = context == null ? null : context.get("threadId");
        return value == null ? null : String.valueOf(value);
    }
}