package com.itajay.superassistant.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Read-only accessor for files that live alongside a skill's SKILL.md
 * (reference guides, templates, examples).
 *
 * <p>Sub-agents only need to read these files. Giving them the general-purpose
 * file tool would also hand them write/delete capability without the main
 * agent's human-in-the-loop approval, so this tool exposes reads alone and
 * confines every path to the skills directory.
 */
@Component
public class SkillResourceTool {

    private static final Logger log = LoggerFactory.getLogger(SkillResourceTool.class);

    /** Must match the directory configured in SkillConfig. */
    private static final String SKILLS_DIR = "app/src/main/resources/skills";
    private static final String CLASSPATH_SKILLS_DIR = "skills";
    private static final long MAX_BYTES = 512 * 1024;

    @Tool(description = """
            Read a supporting file of a skill (reference guides, templates, examples) that sits next to SKILL.md.
            Use this to load a skill's reference documents. Read-only; cannot read or write anything outside the skills directory.""")
    public String readSkillResource(
            @ToolParam(description = "Skill name, i.e. the directory name containing SKILL.md, e.g. 'research_writing_skill'") String skillName,
            @ToolParam(description = "Path of the file relative to the skill directory, e.g. 'references/research-guide.md' or 'template/template.md'") String relativePath) {

        log.info("Reading skill resource: {}/{}", skillName, relativePath);

        try {
            String path = resolve(skillName, relativePath);
            if (path == null) {
                return "Invalid path: must be inside the skills directory (got: " + skillName + "/" + relativePath + ")";
            }

            String content = readFromDisk(path);
            if (content == null) {
                content = readFromClasspath(path);
            }
            if (content == null) {
                return "Skill resource not found: " + path;
            }
            if (content.length() > MAX_BYTES) {
                return "Skill resource too large (max " + MAX_BYTES + " bytes): " + path;
            }
            return content;

        } catch (Exception e) {
            log.error("Failed to read skill resource {}/{}", skillName, relativePath, e);
            return "Failed to read skill resource: " + e.getMessage();
        }
    }

    /** Returns the normalized relative path, or null if it escapes the skills directory. */
    private String resolve(String skillName, String relativePath) {
        if (skillName == null || skillName.isBlank() || relativePath == null || relativePath.isBlank()) {
            return null;
        }
        // Reject absolute paths and traversal before normalizing.
        if (relativePath.startsWith("/") || relativePath.startsWith("\\") || relativePath.contains("..")) {
            return null;
        }
        if (skillName.contains("..") || skillName.contains("/") || skillName.contains("\\")) {
            return null;
        }
        Path base = Path.of(SKILLS_DIR).toAbsolutePath().normalize();
        Path resolved = base.resolve(skillName).resolve(relativePath).normalize();
        if (!resolved.startsWith(base)) {
            return null;
        }
        return SKILLS_DIR + "/" + skillName + "/" + relativePath;
    }

    private String readFromDisk(String relativePath) throws IOException {
        Path file = Path.of(relativePath);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private String readFromClasspath(String relativePath) {
        // Packaged builds ship skills as classpath resources instead of source files.
        String resource = CLASSPATH_SKILLS_DIR + "/" + relativePath.substring(SKILLS_DIR.length() + 1);
        ClassPathResource cp = new ClassPathResource(resource);
        if (!cp.exists()) {
            return null;
        }
        try (var in = cp.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Classpath fallback failed for {}", resource, e);
            return null;
        }
    }
}
