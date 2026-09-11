package com.itajay.superassistant.compact;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.state.ReplaceAllWith;
import com.itajay.superassistant.config.CompactProperties;
import com.itajay.superassistant.plan.PlanModeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Synchronous compaction pipeline.
 *
 * <p>L1-L3 run first. If their result is still critical, full compaction uses
 * the original checkpoint messages to choose a safe prefix/suffix boundary and
 * writes the compacted list back to the current graph state.</p>
 *
 * <p><strong>Critical fix:</strong> every {@code messages} value this hook
 * returns is wrapped in {@link ReplaceAllWith}. The framework's default strategy
 * for {@code messages} is {@code AppendStrategy}, so a bare list would be
 * <em>appended</em> to the existing history instead of replacing it,
 * duplicating context on every agent call. Wrapping the value (as the official
 * {@code MessagesAgentHook} does) replaces it for this node's output only and
 * leaves the model node's append behavior untouched.</p>
 */
@HookPositions(HookPosition.BEFORE_AGENT)
@Component
public class CompactHook extends AgentHook {

    private static final Logger log = LoggerFactory.getLogger(CompactHook.class);
    private final ChatModel chatModel;
    private final CompactThresholds thresholds;
    private final CompactProperties properties;

    private final Map<String, FileReadState> fileStateCache =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, FileReadState> eldest) {
                    return size() > CompactConfig.FILE_STATE_CACHE_MAX_ENTRIES;
                }
            };

    /**
     * Per-thread call bookkeeping (agent-call counter plus the call number of the
     * last full compaction). LRU-bounded, so a long-running server does not keep
     * one entry per thread forever.
     */
    private final Map<String, ThreadCallState> threadCallStates =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ThreadCallState> eldest) {
                    return size() > CompactConfig.FILE_STATE_CACHE_MAX_ENTRIES;
                }
            };

    /** Mutable bookkeeping for a single thread; guarded by {@link #threadCallStates}. */
    private static final class ThreadCallState {
        private int callCount;
        /** Call number of the last full compaction, or {@code null} when none yet. */
        private Integer lastFullCompactCall;
    }

    public CompactHook(ChatModel chatModel,
                       CompactThresholds thresholds,
                       CompactProperties properties) {
        this.chatModel = chatModel;
        this.thresholds = thresholds;
        this.properties = properties;
    }

    @Override
    public String getName() {
        return "context_compact_hook";
    }

    /**
     * Runs compaction <em>after</em> the other BEFORE_AGENT hooks (all of which
     * default to order 0), so the token estimate reflects the final message list
     * that is about to reach the model.
     */
    @Override
    public int getOrder() {
        return 10_000;
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(
            OverAllState state, RunnableConfig config) {
        String threadId = config.threadId().orElse("unknown");
        int callNo = nextCallNo(threadId);

        Optional<Object> messagesValue = state.value("messages");
        if (messagesValue.isEmpty() || !(messagesValue.get() instanceof List<?> list)) {
            return CompletableFuture.completedFuture(Map.of());
        }

        List<Message> originalMessages = castMessages(list);
        if (originalMessages.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        int preTokens = CompactConfig.estimateTokens(originalMessages);
        if (preTokens < thresholds.warningTokens()) {
            return CompletableFuture.completedFuture(replaceMessages(originalMessages));
        }

        List<Message> working = new ArrayList<>(originalMessages);
        working = ToolResultTruncator.truncate(working, threadId);
        working = ContextSnip.snip(working);
        working = MicroCompact.compact(working);
        int afterL3 = CompactConfig.estimateTokens(working);
        log.info("Compaction L1-L3: thread={}, {} -> {} tokens, {} messages",
                threadId, preTokens, afterL3, working.size());

        if (afterL3 < thresholds.criticalTokens()) {
            return CompletableFuture.completedFuture(replaceMessages(working));
        }

        Integer lastCompact = lastFullCompactCall(threadId);
        if (lastCompact != null
                && callNo - lastCompact < properties.getCooldownCalls()) {
            log.info("Full compaction skipped by cooldown (call {} since last full "
                    + "compact at {}); retaining L1-L3 result for thread={}",
                    callNo - lastCompact, lastCompact, threadId);
            return CompletableFuture.completedFuture(replaceMessages(working));
        }

        FileReadState fileState = fileStateFor(threadId);
        fileState.scanMessages(originalMessages);
        Optional<List<Message>> compacted = ContextCompactor.compactSync(
                originalMessages, chatModel, threadId, fileState,
                PlanModeContext.isActive(threadId), properties.getSnapshotKeep());
        if (compacted.isPresent()) {
            List<Message> bounded = ToolResultTruncator.truncate(compacted.get(), threadId);
            int afterFull = CompactConfig.estimateTokens(bounded);
            log.info("Full compaction accepted: thread={}, {} -> {} messages ({} -> {} tokens)",
                    threadId, originalMessages.size(), bounded.size(), preTokens, afterFull);
            if (afterFull >= thresholds.criticalTokens()) {
                log.warn("Full compaction result still above critical threshold ({} >= {}) "
                        + "for thread={}; reduce systemPromptReserveTokens or criticalRatio",
                        afterFull, thresholds.criticalTokens(), threadId);
            }
            markFullCompacted(threadId, callNo);
            return CompletableFuture.completedFuture(replaceMessages(bounded));
        }

        log.warn("Full compaction unavailable; retaining L1-L3 result for thread={}", threadId);
        return CompletableFuture.completedFuture(replaceMessages(working));
    }

    /** Wrap the compacted list so it replaces (not appends to) the history. */
    private static Map<String, Object> replaceMessages(List<Message> messages) {
        return Map.of("messages", ReplaceAllWith.of(messages));
    }

    private FileReadState fileStateFor(String threadId) {
        synchronized (fileStateCache) {
            return fileStateCache.computeIfAbsent(threadId, key -> new FileReadState());
        }
    }

    /** Atomically bump and return this thread's agent-call counter. */
    private int nextCallNo(String threadId) {
        synchronized (threadCallStates) {
            ThreadCallState state = threadCallStates.computeIfAbsent(
                    threadId, key -> new ThreadCallState());
            return ++state.callCount;
        }
    }

    /** Call number of the last full compaction for this thread, or null. */
    private Integer lastFullCompactCall(String threadId) {
        synchronized (threadCallStates) {
            ThreadCallState state = threadCallStates.get(threadId);
            return state == null ? null : state.lastFullCompactCall;
        }
    }

    /** Record that a full compaction just happened on this call. */
    private void markFullCompacted(String threadId, int callNo) {
        synchronized (threadCallStates) {
            threadCallStates.computeIfAbsent(threadId, key -> new ThreadCallState())
                    .lastFullCompactCall = callNo;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Message> castMessages(List<?> list) {
        return (List<Message>) list;
    }
}