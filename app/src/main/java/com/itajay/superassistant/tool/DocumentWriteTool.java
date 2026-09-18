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

/**
 * Writes a research document into {@code investigation/{课题方向}/document/}.
 *
 * <p>Deliberately narrow: the writer agent needs to persist its output, but granting
 * it the general-purpose file tool would also hand it delete and arbitrary-path write
 * capability — and sub-agent tool calls do not pass through the main agent's
 * human-in-the-loop approval. This tool can only ever create a Markdown file inside
 * a topic's {@code document/} folder.</p>
 */
@Component
public class DocumentWriteTool {

    private static final Logger log = LoggerFactory.getLogger(DocumentWriteTool.class);

    private static final String MARKDOWN_EXTENSION = ".md";

    @Tool(description = """
            Save the finished research document as a Markdown file.
            The file is written to investigation/{课题方向}/document/ inside the project root.
            Only writes Markdown documents; cannot write anywhere else.""")
    public String writeResearchDocument(
            @ToolParam(description = "课题方向, the research topic. Becomes the topic folder name — keep it identical across the whole investigation so later runs reuse the same folder.") String topic,
            @ToolParam(description = "Document file name without extension, e.g. '多主体布局控制调研'. Letters, digits, CJK, dash, underscore, dot only.") String documentName,
            @ToolParam(description = "Full Markdown content of the research document") String content) {

        if (content == null || content.isBlank()) {
            return "Error: content is empty, nothing written.";
        }

        String safeName;
        try {
            safeName = WorkspacePaths.slugify(documentName, "文档名");
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        if (WorkspacePaths.hasExtension(safeName, MARKDOWN_EXTENSION)) {
            safeName = safeName.substring(0, safeName.length() - MARKDOWN_EXTENSION.length());
        }

        Path documentsDir;
        Path target;
        try {
            documentsDir = WorkspacePaths.documentsDir(topic);
            target = documentsDir.resolve(safeName + MARKDOWN_EXTENSION).normalize();
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }

        if (!target.startsWith(documentsDir)) {
            return "Error: resolved path escapes the document directory: " + target;
        }

        try {
            Files.createDirectories(documentsDir);
            boolean existed = Files.exists(target);
            Files.writeString(target, content, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);

            log.info("Wrote research document: {} ({} chars, overwritten={})",
                    target, content.length(), existed);

            return "OK: " + (existed ? "overwritten" : "written") + "\n"
                    + "path: " + WorkspacePaths.relative(target) + "\n"
                    + "chars: " + content.length() + "\n"
                    + "topicDir: " + WorkspacePaths.relative(documentsDir.getParent());

        } catch (IOException e) {
            log.error("Failed to write research document to {}", target, e);
            return "Error: could not write document — " + e.getMessage();
        }
    }
}
