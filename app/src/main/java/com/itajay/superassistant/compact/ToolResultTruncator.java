package com.itajay.superassistant.compact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Layer 1: tool-result budget management.
 *
 * <p>Oversized results are stored on disk and replaced with a token-bounded
 * preview plus an explicit pointer to the full content. The storage directory
 * is capped (see {@link CompactConfig#TOOL_RESULT_DIR_MAX_BYTES}): once the cap
 * is exceeded the oldest truncated files are pruned so the directory does not
 * grow without bound.</p>
 */
public final class ToolResultTruncator {

    private static final Logger log = LoggerFactory.getLogger(ToolResultTruncator.class);

    private ToolResultTruncator() {}

    public static List<Message> truncate(List<Message> messages, String threadId) {
        return truncate(messages, threadId, CompactConfig.TOOL_RESULTS_DIR);
    }

    static List<Message> truncate(List<Message> messages, String threadId, Path storageDir) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }

        // Cheap gate: stop at the first oversized result. The old scan BPE-encoded
        // every tool response twice (once for a log-only total, once inside
        // shouldTruncate) even when nothing needed truncating — the common case.
        boolean hasOversized = false;
        outer:
        for (Message message : messages) {
            if (message instanceof ToolResponseMessage responseMessage) {
                for (ToolResponseMessage.ToolResponse response : responseMessage.getResponses()) {
                    if (CompactConfig.shouldTruncate(response.responseData())) {
                        hasOversized = true;
                        break outer;
                    }
                }
            }
        }

        if (!hasOversized) {
            return messages;
        }

        List<Message> result = new ArrayList<>(messages.size());
        for (Message message : messages) {
            if (message instanceof ToolResponseMessage responseMessage) {
                result.add(truncateResponseMessage(responseMessage, threadId, storageDir));
            } else {
                result.add(message);
            }
        }

        log.info("ToolResultTruncator: truncated oversized tool result(s)");
        return List.copyOf(result);
    }

    private static ToolResponseMessage truncateResponseMessage(
            ToolResponseMessage message, String threadId, Path storageDir) {
        List<ToolResponseMessage.ToolResponse> responses = message.getResponses();
        List<ToolResponseMessage.ToolResponse> replacements = new ArrayList<>(responses.size());
        boolean changed = false;

        for (ToolResponseMessage.ToolResponse response : responses) {
            if (!CompactConfig.shouldTruncate(response.responseData())) {
                replacements.add(response);
                continue;
            }

            String original = response.responseData();
            int originalTokens = CompactConfig.estimateTokens(original);
            String savedPath = saveToDisk(threadId, response.id(), original, storageDir);
            if (savedPath == null) {
                replacements.add(response);
                continue;
            }
            String preview = TokenBudgets.previewWithinTokenBudget(
                    original, CompactConfig.TOOL_RESULT_PREVIEW_TOKENS);
            replacements.add(new ToolResponseMessage.ToolResponse(
                    response.id(), response.name(),
                    buildTruncatedContent(originalTokens, preview, savedPath)));
            changed = true;
            log.info("Truncated tool result [id={}, name={}]: {} -> {} estimated tokens; saved to {}",
                    response.id(), response.name(),
                    originalTokens,
                    CompactConfig.estimateTokens(preview),
                    savedPath);
        }

        if (!changed) {
            return message;
        }
        return ToolResponseMessage.builder()
                .responses(replacements)
                .metadata(message.getMetadata())
                .build();
    }

    private static String buildTruncatedContent(
            int originalTokens, String preview, String savedPath) {
        return preview
                + "\n\n[Tool output truncated. Original estimated tokens: "
                + originalTokens
                + ". Full content: " + savedPath + ".]";
    }

    /** Minimum gap between directory-wide prune scans (they walk up to 512MB). */
    private static final long PRUNE_INTERVAL_MS = 5 * 60 * 1000L;
    private static final java.util.concurrent.atomic.AtomicLong lastPruneAt =
            new java.util.concurrent.atomic.AtomicLong(0L);

    private static String saveToDisk(
            String threadId, String toolCallId, String content, Path storageDir) {
        try {
            Path dir = storageDir.resolve(sanitize(threadId));
            Files.createDirectories(dir);
            Path file = dir.resolve(
                    sanitize(toolCallId) + "_" + Instant.now().toEpochMilli() + ".txt");
            Files.writeString(file, content);
            // Pruning walks the whole storage tree; doing it on every save turned a
            // burst of truncations into O(n × files) disk IO. Once per interval is
            // plenty — the cap is a hygiene bound, not a hard quota.
            long now = System.currentTimeMillis();
            long last = lastPruneAt.get();
            if (now - last >= PRUNE_INTERVAL_MS && lastPruneAt.compareAndSet(last, now)) {
                pruneOversized(storageDir);
            }
            return file.toAbsolutePath().toString();
        } catch (IOException e) {
            log.error("Failed to save truncated tool result to disk", e);
            return null;
        }
    }

    /** Delete the oldest files once the storage directory exceeds the byte cap. */
    private static void pruneOversized(Path storageDir) {
        try {
            long total = sizeOf(storageDir);
            if (total <= CompactConfig.TOOL_RESULT_DIR_MAX_BYTES) {
                return;
            }
            List<Path> files;
            try (Stream<Path> stream = Files.walk(storageDir)) {
                files = stream.filter(Files::isRegularFile)
                        .sorted(Comparator.comparingLong(ToolResultTruncator::lastModifiedMillis))
                        .toList();
            }
            for (Path file : files) {
                if (total <= CompactConfig.TOOL_RESULT_DIR_MAX_BYTES) {
                    break;
                }
                long size = Files.size(file);
                Files.deleteIfExists(file);
                total -= size;
                log.info("Pruned truncated tool result {} ({} bytes)", file, size);
            }
        } catch (IOException e) {
            log.warn("Failed to prune truncated tool-result storage", e);
        }
    }

    private static long sizeOf(Path dir) {
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .mapToLong(ToolResultTruncator::safeSize)
                    .sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static long safeSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
