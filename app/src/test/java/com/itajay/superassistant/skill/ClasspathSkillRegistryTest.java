package com.itajay.superassistant.skill;

import com.alibaba.cloud.ai.graph.skills.registry.classpath.ClasspathSkillRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registry must find the packaged skill independently of the process working
 * directory. Before this test existed, the app started with "0 total skills" when
 * launched via {@code mvn -pl app spring-boot:run} (cwd = app/).
 */
class ClasspathSkillRegistryTest {

    @Test
    void loadsTheResearchWritingSkillFromTheClasspath() throws Exception {
        ClasspathSkillRegistry registry = ClasspathSkillRegistry.builder()
                .classpathPath("skills")
                .autoLoad(true)
                .build();

        assertThat(registry.size()).isGreaterThanOrEqualTo(1);
        assertThat(registry.listAll())
                .extracting(metadata -> metadata.getName())
                .contains("research-writing");
        assertThat(registry.readSkillContent("research-writing"))
                .contains("科研课题调研");
    }
}
