package io.docpilot.mcp.config;

import io.docpilot.mcp.personalization.DisabledSemanticSearchService;
import io.docpilot.mcp.personalization.SemanticSearchService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ServiceConfig {
    @Bean
    @ConditionalOnMissingBean(SemanticSearchService.class)
    public SemanticSearchService disabledSemanticSearchService() {
        return new DisabledSemanticSearchService();
    }
}
