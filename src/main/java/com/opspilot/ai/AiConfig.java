package com.opspilot.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AiProperties.class)
class AiConfig {

    /** Only with an API key. Without an {@link Investigator} the assistant reports itself as off. */
    @Bean
    @ConditionalOnExpression("!'${opspilot.ai.api-key:}'.isBlank()")
    ClaudeInvestigator claudeInvestigator(AiProperties properties) {
        return new ClaudeInvestigator(properties);
    }
}
