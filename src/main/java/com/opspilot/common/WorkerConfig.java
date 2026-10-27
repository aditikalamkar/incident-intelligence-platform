package com.opspilot.common;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableConfigurationProperties(WorkerProperties.class)
public class WorkerConfig {

    /** Without this, {@code @Scheduled} methods are inert; the worker beans still exist and can be called directly. */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "opspilot.workers.enabled", havingValue = "true", matchIfMissing = true)
    static class Scheduling {
    }
}
