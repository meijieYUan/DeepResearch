package com.itajay.superassistant.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link PaperDownloadProperties} bound from {@code agent.paper.*}.
 */
@Configuration
@EnableConfigurationProperties(PaperDownloadProperties.class)
public class PaperDownloadPropertiesConfig {
}
