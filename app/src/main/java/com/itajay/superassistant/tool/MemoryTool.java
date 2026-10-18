package com.itajay.superassistant.tool;

import com.itajay.superassistant.workspace.WorkspacePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Persistent memory backed by small Markdown files under {@code .memory/}.
 *
 * <p>IDs are {@code <type initial><two-digit sequence>}. The sequence is the maximum
 * existing suffix <em>among all facts sharing that initial</em> plus one — not the
 * list size — so deleting an entry can never make a later {@code remember} overwrite
 * a still-existing file. {@code preference} and {@code project} share the {@code p}
 * prefix and therefore share one sequence.</p>
 *
 * <p>All mutating operations are serialized: the tool is a singleton and up to 16
 * conversation runs may call it at the same time.</p>
 */
@Component
public class MemoryTool {

    private static final Logger log = LoggerFactory.getLogger(MemoryTool.class);
    private static final int MAX_FACTS = 50;
    private static final Set<String> TYPES =
            Set.of("preference", "project", "event", "knowledge", "contact");

    private final Path memoryRoot;
    private final Path factsDir;
    private final Path indexFile;
    private final Path logFile;

    public MemoryTool() {
        this(WorkspacePaths.root().resolve(".memory"));
    }

    MemoryTool(Path memoryRoot) {
        this.memoryRoot = memoryRoot.toAbsolutePath().normalize();
        this.factsDir = this.memoryRoot.resolve("facts");
        this.indexFile = this.memoryRoot.resolve("MEMORY.md");
        this.logFile = this.memoryRoot.resolve("consolidate-log.md");
        try {
            Files.createDirectories(factsDir);
            if (!Files.exists(indexFile)) {
                rebuildIndex(List.of());
            }
        } catch (IOException e) {
            log.error("Failed to initialize memory directory {}", this.memoryRoot, e);
        }
    }

    // ================================================================
    //  remember — create or update one fact
    // ================================================================

    @Tool(description = "Remember an important fact or preference about the user. Creates or updates a fact file and refreshes the memory index. Types: preference, project, event, knowledge, contact. Importance 1-10 (higher = more important).")
    public synchronized String remember(
            @ToolParam(description = "Fact type: preference, project, event, knowledge, or contact") String type,
            @ToolParam(description = "Short topic/title for this fact, e.g. '编程语言偏好'") String topic,
            @ToolParam(description = "Detailed content of the fact. Include context and evidence.") String content,
            @ToolParam(description = "Importance score 1-10, 10 being most critical") int importance) {
        try {
            List<Fact> facts = loadAllFacts();
            String factType = normalizeType(type);
            int importanceClamped = Math.max(1, Math.min(10, importance));

            // Check if a similar topic already exists
            Fact existing = findSimilar(facts, topic);
            if (existing != null) {
                existing.type = factType;
                existing.topic = topic;
                existing.content = content;
                existing.importance = importanceClamped;
                existing.updated = LocalDateTime.now();
                writeFactFile(existing);
                rebuildIndex(facts);
                log.info("Updated fact [{}]: {}", existing.id, topic);
                return String.format("Updated existing fact [%s] '%s' (importance: %d)",
                        existing.id, topic, importanceClamped);
            }

            String id = nextId(facts, factType);
            Fact fact = new Fact(id, factType, topic, content, importanceClamped,
                    LocalDateTime.now(), LocalDateTime.now());
            writeFactFile(fact);
            facts.add(fact);

            // Enforce the budget. Evicted facts must lose their file too, otherwise
            // loadAllFacts() reads them straight back and the cap never holds.
            List<Fact> removed = new ArrayList<>();
            while (facts.size() > MAX_FACTS) {
                Fact victim = facts.stream()
                        .filter(f -> f != fact)
                        .min(Comparator.comparingInt(f -> f.importance))
                        .orElse(fact);
                facts.remove(victim);
                deleteFactFile(victim);
                removed.add(victim);
            }
            if (!removed.isEmpty()) {
                logRemovals(removed, "budget overflow");
            }

            rebuildIndex(facts);
            if (removed.contains(fact)) {
                log.info("Rejected fact [{}]: memory is full and it has the lowest importance", id);
                return String.format("Fact not stored: memory is full (%d facts) and this entry "
                        + "has the lowest importance. Raise its importance or delete an existing fact.",
                        MAX_FACTS);
            }
            log.info("Remembered fact [{}]: {} (importance: {})", id, topic, importanceClamped);
            return String.format("Fact remembered [%s] '%s' (type: %s, importance: %d/%d, total: %d/%d)",
                    id, topic, factType, importanceClamped, 10, facts.size(), MAX_FACTS);

        } catch (IOException e) {
            log.error("Failed to remember fact", e);
            return "Error saving memory: " + e.getMessage();
        }
    }

    // ================================================================
    //  recall — search facts
    // ================================================================

    @Tool(description = "Recall facts from memory. Searches by topic/type keyword match. Returns matching fact summaries with their IDs for reference.")
    public String recall(
            @ToolParam(description = "Search query to match against fact topics and content") String query) {
        try {
            List<Fact> facts = loadAllFacts();
            if (facts.isEmpty()) return "No memories found. The memory is empty.";

            String lower = query.toLowerCase(Locale.ROOT);
            List<Fact> matches = facts.stream()
                    .filter(f -> f.topic.toLowerCase(Locale.ROOT).contains(lower)
                            || f.type.toLowerCase(Locale.ROOT).contains(lower)
                            || f.content.toLowerCase(Locale.ROOT).contains(lower))
                    .sorted((a, b) -> Integer.compare(b.importance, a.importance))
                    .toList();

            if (matches.isEmpty()) {
                return String.format("No memories match '%s'. Use listMemories to see all facts.", query);
            }

            StringBuilder sb = new StringBuilder("Memory matches for '" + query + "':\n\n");
            for (Fact f : matches) {
                sb.append(formatFactSummary(f)).append("\n");
            }
            return sb.toString().trim();

        } catch (IOException e) {
            return "Error recalling memories: " + e.getMessage();
        }
    }

    // ================================================================
    //  listMemories — list all fact summaries
    // ================================================================

    @Tool(description = "List all remembered facts with their IDs, types, importance, and summaries. Use this to get an overview before deciding what to update or delete.")
    public String listMemories() {
        try {
            List<Fact> facts = loadAllFacts();
            if (facts.isEmpty()) return "Memory is empty. No facts stored yet.";

            Map<String, List<Fact>> grouped = facts.stream()
                    .collect(Collectors.groupingBy(f -> f.type,
                            () -> new LinkedHashMap<>(),
                            Collectors.toList()));

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Memory: %d/%d facts\n\n", facts.size(), MAX_FACTS));

            for (var entry : grouped.entrySet()) {
                sb.append("## ").append(capitalize(entry.getKey())).append(" (").append(entry.getValue().size()).append(")\n");
                for (Fact f : entry.getValue().stream()
                        .sorted((a, b) -> Integer.compare(b.importance, a.importance))
                        .toList()) {
                    sb.append(formatFactSummary(f)).append("\n");
                }
                sb.append("\n");
            }
            return sb.toString().trim();

        } catch (IOException e) {
            return "Error listing memories: " + e.getMessage();
        }
    }

    // ================================================================
    //  deleteFact — delete one fact
    // ================================================================

    @Tool(description = "Delete a specific fact by its ID. Use this to remove outdated or incorrect memories.")
    public synchronized String deleteFact(
            @ToolParam(description = "Fact ID to delete, e.g. p01, j03") String id) {
        try {
            List<Fact> facts = loadAllFacts();
            Fact target = facts.stream().filter(f -> f.id.equals(id)).findFirst().orElse(null);
            if (target == null) return "Fact [" + id + "] not found.";

            deleteFactFile(target);
            facts.remove(target);
            rebuildIndex(facts);
            logRemovals(List.of(target), "manual deletion");
            return String.format("Deleted fact [%s] '%s'", id, target.topic);

        } catch (IOException e) {
            return "Error deleting fact: " + e.getMessage();
        }
    }

    // ================================================================
    //  consolidateMemories — remove low-value facts and trim to budget
    // ================================================================

    @Tool(description = "Consolidate memories by removing facts with importance < 4 and trimming to the 50-fact budget. Returns a summary of what was removed. Call this when memory is full or has low-quality entries.")
    public synchronized String consolidateMemories() {
        try {
            List<Fact> facts = loadAllFacts();
            int before = facts.size();
            List<Fact> removed = new ArrayList<>();

            // Remove low importance (1-3)
            List<Fact> lowImportance = facts.stream()
                    .filter(f -> f.importance < 4).toList();
            for (Fact f : lowImportance) {
                deleteFactFile(f);
                removed.add(f);
            }
            facts.removeAll(lowImportance);

            // If still over budget, remove lowest importance
            if (facts.size() > MAX_FACTS) {
                facts.sort(Comparator.comparingInt(f -> f.importance));
                while (facts.size() > MAX_FACTS) {
                    Fact f = facts.remove(0);
                    deleteFactFile(f);
                    removed.add(f);
                }
            }

            // IDs are stable identifiers, not array indexes. Renumbering them here
            // used to make preference/project (both "p") collide and overwrite each
            // other's files; the index simply reflects the surviving facts now.
            rebuildIndex(facts);
            logRemovals(removed, "consolidation");

            int after = facts.size();
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Memory consolidated: %d -> %d facts (%d removed)\n\n", before, after, before - after));
            if (!removed.isEmpty()) {
                sb.append("Removed:\n");
                for (Fact f : removed) {
                    sb.append(String.format("  - [%s] %s (importance: %d)\n", f.id, f.topic, f.importance));
                }
            }
            sb.append(String.format("\nCurrent: %d/%d facts. Budget healthy.", after, MAX_FACTS));
            return sb.toString();

        } catch (IOException e) {
            return "Error consolidating memories: " + e.getMessage();
        }
    }

    // ================================================================
    //  Internal helpers
    // ================================================================

    private static class Fact {
        String id, type, topic, content;
        int importance;
        LocalDateTime created, updated;

        Fact(String id, String type, String topic, String content,
             int importance, LocalDateTime created, LocalDateTime updated) {
            this.id = id;
            this.type = type;
            this.topic = topic;
            this.content = content;
            this.importance = importance;
            this.created = created;
            this.updated = updated;
        }
    }

    private static String normalizeType(String type) {
        if (type == null) {
            return "knowledge";
        }
        String normalized = type.trim().toLowerCase(Locale.ROOT);
        return TYPES.contains(normalized) ? normalized : "knowledge";
    }

    /**
     * Next free ID for the type's initial. Scans all facts with that prefix, so the
     * sequence is shared by {@code preference}/{@code project} and keeps advancing
     * after deletions.
     */
    private static String nextId(List<Fact> facts, String type) {
        String prefix = type.substring(0, 1);
        int max = 0;
        for (Fact f : facts) {
            if (f.id == null || !f.id.startsWith(prefix) || f.id.length() < 2) {
                continue;
            }
            try {
                max = Math.max(max, Integer.parseInt(f.id.substring(1)));
            } catch (NumberFormatException ignored) {
                // Legacy or hand-edited id: ignore its numeric suffix.
            }
        }
        return prefix + String.format("%02d", max + 1);
    }

    private Fact findSimilar(List<Fact> facts, String topic) {
        String lower = topic.toLowerCase(Locale.ROOT).trim();
        return facts.stream()
                .filter(f -> f.topic.toLowerCase(Locale.ROOT).trim().equals(lower))
                .findFirst().orElse(null);
    }

    private List<Fact> loadAllFacts() throws IOException {
        if (!Files.exists(factsDir)) return new ArrayList<>();
        List<Fact> facts = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(factsDir, "*.md")) {
            for (Path file : stream) {
                try {
                    String text = Files.readString(file);
                    Fact f = parseFactFile(text);
                    if (f != null) facts.add(f);
                } catch (Exception e) {
                    log.warn("Failed to parse fact file: {}", file, e);
                }
            }
        }
        facts.sort((a, b) -> Integer.compare(b.importance, a.importance));
        return facts;
    }

    private Fact parseFactFile(String text) {
        try {
            String id = extractYaml(text, "id");
            String type = extractYaml(text, "type");
            String topic = extractYaml(text, "topic");
            String importanceStr = extractYaml(text, "importance");
            String createdStr = extractYaml(text, "created");
            String updatedStr = extractYaml(text, "updated");

            if (id == null || type == null || topic == null) return null;

            // Extract content after YAML frontmatter
            int contentStart = text.indexOf("---\n");
            if (contentStart >= 0) {
                contentStart = text.indexOf("---\n", contentStart + 4);
                if (contentStart >= 0) contentStart += 4;
            }
            String content = contentStart > 0 ? text.substring(contentStart).trim() : text;

            int importance = parseOrDefault(importanceStr, 5);
            LocalDateTime created = parseDateTime(createdStr);
            LocalDateTime updated = parseDateTime(updatedStr);

            return new Fact(id, type, topic, content, importance, created, updated);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractYaml(String text, String key) {
        Pattern p = Pattern.compile("^" + key + ":\\s*(.+)$", Pattern.MULTILINE);
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    private int parseOrDefault(String s, int def) {
        try { return s != null ? Integer.parseInt(s) : def; } catch (NumberFormatException e) { return def; }
    }

    private LocalDateTime parseDateTime(String s) {
        try {
            return s != null ? LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME) : LocalDateTime.now();
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    private void deleteFactFile(Fact f) {
        try {
            Files.deleteIfExists(factsDir.resolve(f.id + ".md"));
        } catch (IOException e) {
            log.warn("Could not delete fact file [{}]", f.id, e);
        }
    }

    private void writeFactFile(Fact f) throws IOException {
        String content = String.format("""
                ---
                id: %s
                type: %s
                topic: %s
                importance: %d
                created: %s
                updated: %s
                ---

                # %s

                %s
                """,
                f.id, f.type, f.topic, f.importance,
                f.created.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                f.updated.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                f.topic, f.content);
        writeAtomically(factsDir.resolve(f.id + ".md"), content);
    }

    private void rebuildIndex(List<Fact> facts) throws IOException {
        Map<String, List<Fact>> grouped = facts.stream()
                .collect(Collectors.groupingBy(f -> f.type,
                        () -> new LinkedHashMap<>(),
                        Collectors.toList()));

        StringBuilder sb = new StringBuilder();
        sb.append("# Memory Index\n");
        sb.append(String.format("> Last updated: %s\n", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)));
        sb.append(String.format("> Total facts: %d / %d\n\n", facts.size(), MAX_FACTS));

        if (facts.isEmpty()) {
            sb.append("*No facts stored yet. The agent will add facts as it learns about the user.*\n");
        } else {
            for (var entry : grouped.entrySet()) {
                String label = capitalize(entry.getKey());
                sb.append(String.format("## %s (%d)\n", label, entry.getValue().size()));
                for (Fact f : entry.getValue().stream()
                        .sorted((a, b) -> Integer.compare(b.importance, a.importance))
                        .toList()) {
                    String date = f.created.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                    sb.append(String.format("- [%s] %s | %s%d | %s\n",
                            f.id, f.topic, getStarRating(f.importance), f.importance, date));
                }
                sb.append("\n");
            }
        }

        sb.append("---\n");
        sb.append("*This index is auto-generated. Facts are stored in `.memory/facts/`. ");
        sb.append("Use `listMemories` for full details.*\n");

        writeAtomically(indexFile, sb.toString());
    }

    /** Writes via a sibling temp file + move, so a failed write cannot truncate the old file. */
    private static void writeAtomically(Path target, String content) throws IOException {
        Path dir = target.getParent();
        Files.createDirectories(dir);
        Path temp = Files.createTempFile(dir, ".memory-", ".tmp");
        try {
            Files.writeString(temp, content);
            try {
                Files.move(temp, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private String formatFactSummary(Fact f) {
        String snippet = f.content.length() > 80 ? f.content.substring(0, 80) + "..." : f.content;
        return String.format("[%s] %s | %s | %s%d | %s — %s",
                f.id, capitalize(f.type), f.topic, getStarRating(f.importance), f.importance,
                f.created.format(DateTimeFormatter.ofPattern("MM-dd")), snippet);
    }

    private void logRemovals(List<Fact> removed, String reason) throws IOException {
        if (removed.isEmpty()) {
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add(String.format("## %s — %s (%d facts removed)",
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), reason, removed.size()));
        for (Fact f : removed) {
            lines.add(String.format("- [%s] %s (importance: %d)", f.id, f.topic, f.importance));
        }
        lines.add("");
        if (Files.exists(logFile)) {
            lines.addAll(Files.readAllLines(logFile));
        }
        writeAtomically(logFile, String.join("\n", lines) + "\n");
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    private String getStarRating(int importance) {
        int stars = Math.max(1, Math.min(5, importance / 2));
        return "★".repeat(stars);
    }
}
