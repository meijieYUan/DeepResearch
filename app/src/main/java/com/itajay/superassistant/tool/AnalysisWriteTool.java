package com.itajay.superassistant.tool;

import com.itajay.superassistant.workspace.WorkspacePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;

/**
 * Persists one paper's close reading into {@code investigation/{课题方向}/analysis/}.
 *
 * <p>This file is the single copy of the paper's four-dimension detail. The research
 * document used to repeat all of it, which made the document grow linearly with the
 * number of papers and eventually overflowed the model's output ceiling mid-call.
 * Now the document only summarises and compares, and points here for the detail —
 * which also gives a human something to open and a reviewer something to check
 * formulas against.</p>
 *
 * <p>Deliberately narrow, like {@link DocumentWriteTool}: it can only ever create a
 * Markdown file inside one topic's {@code analysis/} folder. The analyst agent needs
 * to persist its output; handing it the general-purpose file tool would also hand it
 * delete and arbitrary-path write capability, and sub-agent tool calls do not pass
 * through the main agent's human-in-the-loop approval.</p>
 *
 * <p>The header is written by this tool rather than by the model, so the metadata
 * the document and the reviewer rely on stays machine-reliable. {@code sourceLevel}
 * in particular is a required argument: it forces the analyst to declare how much of
 * the paper it could actually read, which is the single most important caveat on
 * everything below it.</p>
 */
@Component
public class AnalysisWriteTool {

    private static final Logger log = LoggerFactory.getLogger(AnalysisWriteTool.class);

    private static final String MARKDOWN_EXTENSION = ".md";

    /** The source levels the analysis guide defines (analysis-guide.md §3). */
    private static final Set<String> SOURCE_LEVELS =
            Set.of("FULL_TEXT", "ABSTRACT_ONLY", "SNIPPET", "UNKNOWN");

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Tool(description = """
            Save the close reading of ONE paper — its four-dimension analysis — as a Markdown file.
            Written to investigation/{课题方向}/analysis/{论文短名}.md inside the project root.
            Call this once per paper, after reading its text. Re-calling with the same short name overwrites.
            Only writes Markdown; cannot write anywhere else.""")
    public String writePaperAnalysis(
            @ToolParam(description = "课题方向, the research topic. Must be identical to the one used for the papers and the document.") String topic,
            @ToolParam(description = "论文短名, the paper's short name — the same one the downloaded PDF uses, e.g. 'MS-Diffusion'.") String paperShortName,
            @ToolParam(description = "How much of the paper you could actually read: FULL_TEXT, ABSTRACT_ONLY, SNIPPET, or UNKNOWN.") String sourceLevel,
            @ToolParam(description = "One sentence (20-60 chars) saying what this paper does and where it sits in the topic. Shown in the document's paper list, so it must stand alone.") String oneLineSummary,
            @ToolParam(description = "The four-dimension analysis in Markdown: 论文概要与作者意图 / 方法框架 / 关键机制与创新点 / 训练目标. Start headings at ## — the title is added for you.") String content) {

        if (content == null || content.isBlank()) {
            return "Error: content is empty, nothing written.";
        }

        String name;
        Path analysisDir;
        Path target;
        try {
            name = AnalysisStore.nameFor(paperShortName);
            analysisDir = AnalysisStore.analysisRoot(topic);
            target = analysisDir.resolve(name + MARKDOWN_EXTENSION).normalize();
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }

        if (!target.startsWith(analysisDir)) {
            return "Error: resolved path escapes the analysis directory: " + target;
        }

        String level = normaliseSourceLevel(sourceLevel);
        String summary = oneLineSummary == null ? "" : oneLineSummary.strip();
        StringBuilder file = new StringBuilder();
        file.append("# ").append(name).append("\n\n");
        file.append("> 来源等级：").append(level).append('\n');
        if (!summary.isEmpty()) {
            file.append("> 一句话定位：").append(summary).append('\n');
        }
        file.append("> 生成时间：").append(LocalDateTime.now().format(TIMESTAMP)).append("\n\n");
        file.append("---\n\n").append(content.strip()).append('\n');

        try {
            Files.createDirectories(analysisDir);
            boolean existed = Files.exists(target);
            Files.writeString(target, file.toString(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);

            log.info("Wrote paper analysis: {} ({} chars, level={}, overwritten={})",
                    target, content.length(), level, existed);

            StringBuilder ok = new StringBuilder("OK: ")
                    .append(existed ? "overwritten" : "written").append('\n')
                    .append("path: ").append(WorkspacePaths.relative(target)).append('\n')
                    .append("sourceLevel: ").append(level).append('\n')
                    .append("chars: ").append(content.length());
            if (!level.equalsIgnoreCase(sourceLevel == null ? "" : sourceLevel.trim())) {
                ok.append("\nNote: sourceLevel was not one of FULL_TEXT / ABSTRACT_ONLY / SNIPPET / "
                        + "UNKNOWN, so it was recorded as UNKNOWN. Re-call with a valid value if that "
                        + "misrepresents what you read.");
            }
            return ok.toString();

        } catch (IOException e) {
            log.error("Failed to write paper analysis to {}", target, e);
            return "Error: could not write analysis — " + e.getMessage();
        }
    }

    /** Uppercases and validates the declared source level, falling back to UNKNOWN. */
    private static String normaliseSourceLevel(String raw) {
        if (raw == null || raw.isBlank()) {
            return "UNKNOWN";
        }
        String value = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return SOURCE_LEVELS.contains(value) ? value : "UNKNOWN";
    }
}
