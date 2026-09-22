package com.itajay.superassistant.compact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks which files have been read or written during the current session,
 * with timestamps.
 *
 * <p>The state is built from the <em>tool-call arguments</em> of
 * {@code readFile}/{@code writeFile} (the {@code filePath} parameter), not from
 * tool-response payloads — a read result is the file <em>content</em>, which is
 * a wrong source for path extraction. It is used by {@link ContextCompactor} to
 * decide which files to re-inject after a full LLM compaction.</p>
 */
public class FileReadState {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Instant> accessTimes = new LinkedHashMap<>();

    /** Record a file access (used by callers that observe tool executions). */
    public synchronized void recordAccess(String filePath) {
        if (filePath != null && !filePath.isBlank()) {
            accessTimes.put(normalize(filePath), Instant.now());
        }
    }

    /**
     * Scan messages for file read/write tool calls and rebuild the state.
     * Call this before compaction so the cache reflects the latest history.
     *
     * <p>Access times are derived from each message's <em>position</em> in the
     * history, not from the scan's wall clock: stamping everything {@code now()}
     * (the old behavior) made "most recent" degrade to insertion order of the scan,
     * so {@link #getRecentFiles} actually returned the <em>earliest</em> files seen.
     * Position-derived instants sit just below the real clock, so live
     * {@link #recordAccess} observations always outrank scan entries.</p>
     */
    public synchronized void scanMessages(List<Message> messages) {
        if (messages == null) {
            return;
        }
        Instant base = Instant.now().minusSeconds(messages.size() + 1L);
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (msg instanceof AssistantMessage assistantMessage
                    && assistantMessage.hasToolCalls()) {
                for (AssistantMessage.ToolCall call : assistantMessage.getToolCalls()) {
                    if (isFileAccessTool(call.name())) {
                        String path = extractFilePath(call.arguments());
                        if (path != null) {
                            // put (not putIfAbsent): a later access to the same file wins.
                            accessTimes.put(normalize(path), base.plusSeconds(i));
                        }
                    }
                }
            }
        }
    }

    /**
     * Get the most recently accessed files, sorted by timestamp (newest first).
     *
     * @param maxFiles maximum number of files to return
     * @return list of file paths, newest first
     */
    public synchronized List<String> getRecentFiles(int maxFiles) {
        return accessTimes.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .limit(maxFiles)
                .map(Map.Entry::getKey)
                .toList();
    }

    public synchronized int size() {
        return accessTimes.size();
    }

    // ── helpers ──

    private static boolean isFileAccessTool(String name) {
        return "readFile".equals(name) || "writeFile".equals(name);
    }

    /** Parse the {@code filePath} parameter out of a tool-call arguments JSON. */
    static String extractFilePath(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(arguments);
            if (node == null || !node.isObject()) {
                return null;
            }
            for (String field : new String[] {"filePath", "file_path", "path"}) {
                JsonNode value = node.get(field);
                if (value != null && value.isTextual() && !value.asText().isBlank()) {
                    return value.asText();
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalize(String path) {
        try {
            return Path.of(path).normalize().toString().replace('\\', '/');
        } catch (Exception e) {
            return path;
        }
    }
}
