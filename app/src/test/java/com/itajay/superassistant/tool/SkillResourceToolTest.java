package com.itajay.superassistant.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SkillResourceToolTest {

    private final SkillResourceTool tool = new SkillResourceTool();

    @Test
    void readsAReferenceGuideThroughTheClasspath() {
        String content = tool.readSkillResource("research-writing",
                "references/research-guide.md");

        assertThat(content).isNotBlank().doesNotStartWith("Skill resource not found")
                .doesNotStartWith("Failed to read");
    }

    @Test
    void rejectsTraversalOutsideTheSkillDirectory() {
        assertThat(tool.readSkillResource("research-writing", "../../pom.xml"))
                .startsWith("Invalid path");
        assertThat(tool.readSkillResource("../other", "SKILL.md"))
                .startsWith("Invalid path");
    }
}
