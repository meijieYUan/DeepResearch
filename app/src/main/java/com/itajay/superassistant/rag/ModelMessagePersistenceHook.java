package com.itajay.superassistant.rag;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.itajay.superassistant.service.ChatMessagePersistenceService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Persists every renderable main-agent model response. Tool-call-only model
 * messages have no text and are skipped; their execution remains checkpoint
 * state rather than frontend chat history.
 */
@HookPositions(HookPosition.AFTER_MODEL)
@Component
public class ModelMessagePersistenceHook extends ModelHook {

    private final ChatMessagePersistenceService persistenceService;

    public ModelMessagePersistenceHook(ChatMessagePersistenceService persistenceService) {
        this.persistenceService = persistenceService;
    }

    @Override
    public String getName() {
        return "model_message_persistence";
    }

    @Override
    public CompletableFuture<Map<String, Object>> afterModel(
            OverAllState state, RunnableConfig config) {
        config.threadId().ifPresent(threadId ->
                state.value("messages")
                        .filter(List.class::isInstance)
                        .map(List.class::cast)
                        .ifPresent(messages -> persistLatestAssistant(threadId, messages)));
        return CompletableFuture.completedFuture(Map.of());
    }

    private void persistLatestAssistant(String threadId, List<?> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AssistantMessage assistantMessage) {
                persistenceService.saveAssistantMessage(threadId, assistantMessage);
                return;
            }
        }
    }
}
