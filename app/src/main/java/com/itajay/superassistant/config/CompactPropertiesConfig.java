package com.itajay.superassistant.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link CompactProperties} bound from {@code agent.compact.*}.
 */
@Configuration
@EnableConfigurationProperties(CompactProperties.class)
public class CompactPropertiesConfig {
}
