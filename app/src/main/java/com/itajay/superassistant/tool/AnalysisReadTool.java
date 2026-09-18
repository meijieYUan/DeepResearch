package com.itajay.superassistant.tool;

import com.itajay.superassistant.workspace.WorkspacePaths;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads back a paper's persisted close reading from
 * {@code investigation/{课题方向}/analysis/}.
 *
 * <p>Read-only, one file at a time. The writer uses it to pull in only the papers a
 * comparison actually rests on, instead of holding every paper's detail at once;
 * the reviewer uses it to check the detail the document no longer carries. Both get
 * the same call.</p>
 *
 * <p>One file per call is the point, not an accident — the whole reason the detail
 * was moved out of the document is that loading all of it into one context is what
 * made the run fail.</p>
 */
@Component
public class AnalysisReadTool {

    @Tool(description = """
            Read the saved four-dimension analysis of one paper (produced by analyzePapers).
            Returns the file's Markdown, including its 来源等级 header — check that before
            trusting any formula in it. Reads one paper per call.""")
    public String readPaperAnalysis(
            @ToolParam(description = "课题方向, the research topic the paper belongs to") String topic,
            @ToolParam(description = "论文短名, the paper's short name, e.g. 'MS-Diffusion'. A path under investigation/.../analysis/ also works.") String paperShortName) {

        Path file;
        try {
            file = AnalysisStore.resolveMarkdown(topic, paperShortName);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }

        if (!Files.isRegularFile(file)) {
            return "Error: no analysis found for \"" + paperShortName + "\" (expected "
                    + WorkspacePaths.relative(file) + ").\n"
                    + "Run analyzePapers first. If it reported this paper as failed, the paper could not "
                    + "be read — treat it as ABSTRACT_ONLY and say so in the document instead of filling "
                    + "the gap from memory.";
        }

        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Error: could not read the analysis for \"" + paperShortName + "\" — " + e.getMessage();
        }
    }
}
