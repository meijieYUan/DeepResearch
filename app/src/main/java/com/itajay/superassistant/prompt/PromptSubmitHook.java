package com.itajay.superassistant.prompt;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.itajay.superassistant.plan.PlanModeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Inject the dynamic system prompts (user memory profile, plan-mode guidance)
 * into every model request, just before the model is called.
 *
 * <p>This is a {@link ModelInterceptor} (registered on the main agent via
 * {@code Builder.interceptors(...)}), not a graph hook. The prompts are merged
 * into the request's {@link ModelRequest#getSystemMessage() system message} and
 * therefore <strong>never</strong> enter the {@code messages} list: they are not
 * written to the checkpoint, not part of the renderable chat history, and cannot
 * be duplicated by the {@code messages} key strategy (which appends).</p>
 *
 * <p>The prompts are rebuilt on every model call, so a memory update or a
 * plan-mode toggle takes effect immediately — no staleness between turns.</p>
 */
@Component
public class PromptSubmitHook extends ModelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(PromptSubmitHook.class);

    /** User memory file, maintained by {@code MemoryTool}. */
    private static final Path MEMORY_FILE = Path.of(".memory", "MEMORY.md");

    /** Request-context key carrying the thread id (set by the chat controller). */
    private static final String THREAD_ID_KEY = "threadId";

    /** Hard cap on the memory section to keep the system prompt bounded. */
    private static final int MEMORY_MAX_CHARS = 3_000;

    @Override
    public String getName() {
        return "prompt_submit_hook";
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        String threadId = resolveThreadId(request);
        String dynamicPrompt = buildDynamicPrompt(threadId);
        if (dynamicPrompt == null || dynamicPrompt.isBlank()) {
            return handler.call(request);
        }

        SystemMessage enhanced = enhanceSystemMessage(request.getSystemMessage(), dynamicPrompt);
        ModelRequest updated = ModelRequest.builder(request).systemMessage(enhanced).build();
        log.debug("PromptSubmitHook: injected dynamic system prompt for thread={}", threadId);
        return handler.call(updated);
    }

    private static String resolveThreadId(ModelRequest request) {
        Map<String, Object> context = request.getContext();
        if (context == null) {
            return null;
        }
        Object value = context.get(THREAD_ID_KEY);
        return value == null ? null : String.valueOf(value);
    }

    private static String buildDynamicPrompt(String threadId) {
        StringBuilder prompt = new StringBuilder();
        String memoryPrompt = buildMemoryPrompt();
        if (memoryPrompt != null) {
            prompt.append(memoryPrompt);
        }
        if (threadId != null && PlanModeContext.isEnabled(threadId)) {
            if (!prompt.isEmpty()) {
                prompt.append("\n\n");
            }
            prompt.append(buildPlanModePrompt());
        }
        return prompt.isEmpty() ? null : prompt.toString();
    }

    /** Append the dynamic prompt to the existing system message (if any). */
    private static SystemMessage enhanceSystemMessage(SystemMessage existing, String extra) {
        if (existing == null || existing.getText() == null || existing.getText().isBlank()) {
            return new SystemMessage(extra);
        }
        return new SystemMessage(existing.getText() + "\n\n" + extra);
    }

    private static String buildMemoryPrompt() {
        try {
            if (!Files.exists(MEMORY_FILE)) {
                return null;
            }
            String content = Files.readString(MEMORY_FILE).trim();
            if (content.isBlank()) {
                return null;
            }
            if (content.length() > MEMORY_MAX_CHARS) {
                content = content.substring(0, MEMORY_MAX_CHARS)
                        + "\n\n[...truncated, use listMemories for the full list]";
            }
            return """
                    ## User Memory Profile

                    The following is what we know about the user from past interactions.
                    Use this context to personalize responses. When you learn something new
                    or important about the user, call the remember tool.

                    %s
                    """.formatted(content);
        } catch (IOException e) {
            log.warn("Failed to read memory file {}", MEMORY_FILE, e);
            return null;
        }
    }

    private static String buildPlanModePrompt() {
        return """
                ## Plan Mode Active - RESTRICTED OPERATION

                You are currently in Plan Mode. You have ONLY the following permissions:
                - Read and analyze code/files
                - Search the web for information
                - Write plan documents to plans/{threadId}.md
                - Ask the user questions for clarification

                STRICTLY FORBIDDEN:
                - Writing or modifying business code
                - Deleting any files
                - Executing terminal commands
                - Any other destructive or mutating operations

                """;
    }
}
