package com.itajay.superassistant.skill;

import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.classpath.ClasspathSkillRegistry;
import com.itajay.superassistant.workspace.WorkspacePaths;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SkillConfig {

    /**
     * Loads {@code classpath:skills/*}{@code /**}{@code /SKILL.md}.
     *
     * <p>This used to be a {@link com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry}
     * with the relative path {@code app/src/main/resources/skills}. {@code spring-boot:run}
     * starts the forked JVM in the {@code app/} module directory, so that path resolved
     * to {@code app/app/src/main/resources/skills} and the registry came up empty
     * ("Skills reloaded: 0 total skills") — the main agent genuinely had no skill to
     * list. The classpath registry is independent of the working directory and also
     * works from a packaged jar.</p>
     */
    @Bean
    public SkillsAgentHook skillsAgentHook() {
        SkillRegistry skillRegistry = ClasspathSkillRegistry.builder()
                .classpathPath("skills")
                // Materialized copies of the skill files. The default is /tmp, which
                // is not a useful path to show the model (and is not stable across
                // restarts on every platform).
                .basePath(WorkspacePaths.root().resolve(".cache").toString())
                .autoLoad(true)
                .build();
        return SkillsAgentHook.builder()
                .skillRegistry(skillRegistry)
                .autoReload(true)
                .build();
    }
}
