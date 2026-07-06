package com.vprok.forms.config;

import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorProvider")
public class JpaAuditingConfig {

    /**
     * No authentication exists yet; created_by/updated_by stay null until a
     * real principal is available (both columns are nullable for this reason).
     */
    @Bean
    public AuditorAware<String> auditorProvider() {
        return Optional::empty;
    }
}
