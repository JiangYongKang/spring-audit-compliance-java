package com.github.highcumontoa.springauditcompliancejava.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RetentionProperties.class)
public class AppConfig {
}
