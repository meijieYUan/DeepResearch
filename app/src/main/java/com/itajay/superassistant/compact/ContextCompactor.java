package com.itajay.superassistant.compact;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * Full compaction summarizes an original-message prefix and retains a safe
 * suffix. The result is written directly to the current graph state, so the
 * process no longer depends on an async cross-turn session-memory file.
 */
public final class ContextCompactor {

    private static final Logger log = LoggerFactory.getLogger(ContextCompactor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String COMPACT_PROMPT = """
            你是一个上下文压缩器。请对以下对话历史进行完整而精炼的总结。

            ## 必须包含的内容（逐一覆盖，不得遗漏）：
            1. 用户的主要请求和意图，包括原始措辞和深层意图。
            2. 关键技术概念、架构决策、框架选择及其原因。
            3. 涉及的文件和代码片段，保留完整路径、关键代码和行号引用。
            4. 遇到的错误、排查过程和最终解决方案。
            5. 按时间线描述问题解决过程。
            6. 逐条列出用户的每条输入，不可合并、不可省略。
            7. 待完成任务和当前工作状态。
            8. 建议的下一步。

            ## 格式要求：
            - 使用与对话相同的语言。
            - 只输出总结本身。
            - 保持结构化且精炼。
            - 总长度控制在 %d tokens 以内。

            对话内容：
            """.formatted(CompactConfig.SUMMARY_TOKEN_BUDGET);

    private static final String MERGE_PROMPT = """
            你是一个上下文压缩器。以下内容是同一对话按时间顺序分段生成的摘要。
            请把它们合并成一个最终总结，保留所有用户请求、关键决策、文件路径、
            错误修复、任务状态和下一步建议。不要遗漏任何分段中的独立信息。

            分段摘要：
            """;

    private static final int COMPACT_INPUT_MAX_CHARS = 30_000;

    /** Bounded parallelism for chunk summarization; each task is one blocking LLM call. */
    private static final int SUMMARY_PARALLELISM = 4;
    /** Overall deadline for the parallel chunk phase; exceeding it degrades to L1-L3. */
    private static final Duration SUMMARY_TIMEOUT = Duration.ofMinutes(5);
    private static final ExecutorService SUMMARY_EXECUTOR =
            Executors.newFixedThreadPool(SUMMARY_PARALLELISM, task -> {
                Thread thread = new Thread(task, "compact-summary");
                thread.setDaemon(true);
                return thread;
            });

    private record SnapshotMessage(String type, String content) {}

    private record Snapshot(
            String threadId,
            Instant compactedAt,
            int originalMessageCount,
            int cutoffIndex,
            int retainedMessageCount,
            String summary,
            List<String> recoveredFilePaths,
            List<SnapshotMessage> messages
    ) {}

    private ContextCompactor() {}

    public static Optional<List<Message>> compactSync(
            List<Message> originalMessages,
            ChatModel chatModel,
            String threadId,
            FileReadState fileState,
            boolean planActive,
            int snapshotKeep) {
        if (originalMessages == null || originalMessages.isEmpty()) {
            return Optional.empty();
        }

        int candidate = Math.max(0,
                originalMessages.size() - CompactConfig.FULL_COMPACT_KEEP_RECENT_MESSAGES);
        int cutoff = ToolCallIntegrity.safeCutoff(originalMessages, candidate);
        if (cutoff <= 0) {
            return Optional.empty();
        }

        List<Message> prefix = originalMessages.subList(0, cutoff);
        List<Message> suffix = originalMessages.subList(cutoff, originalMessages.size());
        String summary = generateSummary(prefix, chatModel);
        if (summary == null || summary.isBlank()) {
            log.warn("Full compact skipped because summary generation failed for thread={}", threadId);
            return Optional.empty();
        }
        summary = enforceSummaryTokenBudget(summary);

        List<String> recoveredPaths = fileState.getRecentFiles(
                CompactConfig.POST_COMPACT_MAX_FILES);
        String planContent = loadPlanFile(threadId);
        if (planActive) {
            planContent = planContent == null || planContent.isBlank()
                    ? "[Plan mode is active. Continue read/search/plan only until the user approves implementation.]"
                    : planContent + "\n\n[Plan mode is active. Continue read/search/plan only until the user approves implementation.]";
        }

        List<Message> compacted = new ArrayList<>(suffix.size() + 4);
        compacted.add(new SystemMessage(
                "[COMPACT BOUNDARY — context before this point summarized]\n"
                + "[Compacted at: " + Instant.now() + "]\n"
                + "[Original messages: " + originalMessages.size()
                + "; summarized: " + cutoff + "; retained: " + suffix.size() + "]"
        ));
        compacted.add(new UserMessage(
                "[Context Compaction Summary — " + cutoff + " earlier messages summarized]\n\n"
                + summary
        ));
        compacted.addAll(buildRecoveredMessages(recoveredPaths, planContent));
        compacted.addAll(suffix);

        archiveSnapshot(originalMessages, threadId, cutoff, summary, recoveredPaths, snapshotKeep);
        log.info("Full compact completed: thread={}, original={}, summarized={}, retained={}",
                threadId, originalMessages.size(), cutoff, suffix.size());
        return Optional.of(List.copyOf(compacted));
    }

    /** Trim an over-long summary to the configured token budget. */
    private static String enforceSummaryTokenBudget(String summary) {
        if (CompactConfig.estimateTokens(summary) <= CompactConfig.SUMMARY_TOKEN_BUDGET) {
            return summary;
        }
        String trimmed = TokenBudgets.previewWithinTokenBudget(
                summary, CompactConfig.SUMMARY_TOKEN_BUDGET);
        log.warn("Full compact summary truncated to {} tokens (was ~{})",
                CompactConfig.estimateTokens(trimmed),
                CompactConfig.estimateTokens(summary));
        return trimmed + "\n...[summary truncated]";
    }

    private static String generateSummary(List<Message> messages, ChatModel chatModel) {
        List<String> chunks = buildChunks(messages);
        if (chunks.isEmpty()) {
            return null;
        }

        if (chunks.size() == 1) {
            String summary = callSummaryModel(COMPACT_PROMPT + chunks.get(0), chatModel);
            return summary == null || summary.isBlank() ? null : summary.trim();
        }

        // Chunk summaries are independent LLM calls — run them in parallel under one
        // overall deadline. The old serial loop could block the BEFORE_AGENT hook (and
        // with it the whole run) for minutes on a long history. On timeout or any chunk
        // failure we return null, and CompactHook degrades to the L1-L3 result.
        List<CompletableFuture<String>> futures = chunks.stream()
                .map(chunk -> CompletableFuture.supplyAsync(
                        () -> callSummaryModel(COMPACT_PROMPT + chunk, chatModel),
                        SUMMARY_EXECUTOR))
                .toList();
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(SUMMARY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            futures.forEach(f -> f.cancel(true));
            log.error("Full compact aborted: {} chunk summaries did not finish within {}",
                    chunks.size(), SUMMARY_TIMEOUT);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            futures.forEach(f -> f.cancel(true));
            log.warn("Full compact interrupted during chunk summarization");
            return null;
        } catch (ExecutionException e) {
            log.error("Full compact chunk summarization failed", e.getCause());
            return null;
        }

        List<String> summaries = new ArrayList<>(futures.size());
        for (CompletableFuture<String> future : futures) {
            String summary = future.getNow(null);
            if (summary == null || summary.isBlank()) {
                return null;
            }
            summaries.add(summary);
        }

        StringBuilder mergedInput = new StringBuilder();
        for (int i = 0; i < summaries.size(); i++) {
            mergedInput.append("\n\n### Section ").append(i + 1).append("\n")
                    .append(summaries.get(i));
        }
        return callSummaryModel(MERGE_PROMPT + mergedInput, chatModel);
    }

    private static List<String> buildChunks(List<Message> messages) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (Message message : messages) {
            String rendered = renderMessage(message);
            if (rendered.isBlank()) {
                continue;
            }

            int position = 0;
            while (position < rendered.length()) {
                int end = Math.min(rendered.length(), position + COMPACT_INPUT_MAX_CHARS);
                String part = rendered.substring(position, end);
                if (!current.isEmpty()
                        && current.length() + part.length() > COMPACT_INPUT_MAX_CHARS) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                current.append(part);
                position = end;
            }
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    private static String renderMessage(Message message) {
        StringBuilder rendered = new StringBuilder()
                .append('[').append(message.getMessageType()).append("] ");

        String text = message.getText();
        if (text != null && !text.isBlank()) {
            rendered.append(text.trim()).append('\n');
        }

        if (message instanceof AssistantMessage assistantMessage) {
            for (AssistantMessage.ToolCall call : assistantMessage.getToolCalls()) {
                rendered.append("[TOOL_CALL id=")
                        .append(call.id())
                        .append(", name=")
                        .append(call.name())
                        .append("] ")
                        .append(compactText(call.arguments()))
                        .append('\n');
            }
        } else if (message instanceof ToolResponseMessage responseMessage) {
            for (ToolResponseMessage.ToolResponse response : responseMessage.getResponses()) {
                rendered.append("[TOOL_RESULT id=")
                        .append(response.id())
                        .append(", name=")
                        .append(response.name())
                        .append("] ")
                        .append(compactText(response.responseData()))
                        .append('\n');
            }
        }
        return rendered.toString();
    }

    private static String compactText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.length() <= 4_000) {
            return trimmed;
        }
        return trimmed.substring(0, 4_000) + "\n[...summary input truncated...]";
    }

    private static String callSummaryModel(String promptText, ChatModel chatModel) {
        try {
            var response = chatModel.call(new Prompt(new UserMessage(promptText)));
            String text = response.getResult().getOutput().getText();
            return text == null ? null : text.trim();
        } catch (Exception e) {
            log.error("Full compact model call failed", e);
            return null;
        }
    }

    /**
     * Build the attachments re-injected after compaction: recently read/written
     * files plus the active plan file.
     *
     * <p>The user memory profile is intentionally <em>not</em> attached here:
     * {@code PromptSubmitHook} injects the current MEMORY.md into the system
     * message on every model call, so attaching it again would duplicate it.</p>
     */
    private static List<Message> buildRecoveredMessages(
            List<String> recoveredPaths, String planContent) {
        List<Message> messages = new ArrayList<>();
        int remainingBudget = CompactConfig.POST_COMPACT_FILE_BUDGET;
        for (String path : recoveredPaths) {
            if (remainingBudget <= 0 || isDedicatedRecoveryFile(path)) {
                continue;
            }
            try {
                Path file = Path.of(path);
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                String fullContent = Files.readString(file);
                int fileBudget = Math.min(
                        remainingBudget, CompactConfig.POST_COMPACT_MAX_TOKENS_PER_FILE);
                String content = TokenBudgets.previewWithinTokenBudget(fullContent, fileBudget);
                if (content.length() < fullContent.length()) {
                    content += "\n...[recovered file truncated]";
                }
                remainingBudget -= CompactConfig.estimateTokens(content);
                messages.add(new SystemMessage(
                        "[Post-compact recovered: " + path + "]\n---\n" + content + "\n---"));
            } catch (IOException e) {
                log.warn("Failed to recover file after compaction: {}", path, e);
            }
        }
        if (planContent != null && !planContent.isBlank()) {
            messages.add(new SystemMessage(planContent));
        }
        return messages;
    }

    private static boolean isDedicatedRecoveryFile(String path) {
        String normalized = path.replace('\\', '/');
        return normalized.contains("/plans/")
                || normalized.contains("/.memory/")
                || normalized.startsWith("plans/")
                || normalized.startsWith(".memory/");
    }

    private static void archiveSnapshot(
            List<Message> messages,
            String threadId,
            int cutoff,
            String summary,
            List<String> recoveredPaths,
            int snapshotKeep) {
        try {
            Path dir = CompactConfig.COMPACT_SNAPSHOTS_DIR.resolve(sanitize(threadId));
            Files.createDirectories(dir);
            Path file = dir.resolve("compact_" + Instant.now().toEpochMilli() + ".json");
            Snapshot snapshot = new Snapshot(
                    threadId,
                    Instant.now(),
                    messages.size(),
                    cutoff,
                    messages.size() - cutoff,
                    summary,
                    recoveredPaths,
                    messages.stream()
                            .map(message -> new SnapshotMessage(
                                    message.getMessageType().name(), message.getText()))
                            .toList()
            );
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), snapshot);
            pruneSnapshots(dir, snapshotKeep);
            log.info("Archived full-compaction snapshot to {}", file);
        } catch (IOException e) {
            log.warn("Failed to archive full-compaction snapshot", e);
        }
    }

    /** Keep only the newest {@code keep} snapshots in the thread directory. */
    private static void pruneSnapshots(Path dir, int keep) throws IOException {
        if (keep <= 0) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> snapshots = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith("compact_"))
                    .sorted(Comparator.comparingLong(ContextCompactor::lastModifiedMillis))
                    .toList();
            int toRemove = snapshots.size() - keep;
            for (int i = 0; i < toRemove; i++) {
                Files.deleteIfExists(snapshots.get(i));
            }
        }
    }

    private static long lastModifiedMillis(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    static String loadPlanFile(String threadId) {
        try {
            Path planFile = Path.of("plans", sanitize(threadId) + ".md");
            if (!Files.exists(planFile)) {
                return null;
            }
            String content = Files.readString(planFile);
            if (content.length() > 8000) {
                content = content.substring(0, 8000) + "\n...[plan truncated]";
            }
            return "[Recovered plan: plans/" + threadId + ".md]\n\n" + content;
        } catch (IOException e) {
            return null;
        }
    }

    static String sanitize(String value) {
        if (value == null) {
            return "unknown";
        }
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}