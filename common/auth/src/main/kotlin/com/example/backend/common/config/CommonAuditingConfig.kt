package com.example.backend.common.config

import com.example.backend.common.auditing.AuditorAwareImpl
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.domain.AuditorAware
import org.springframework.data.jpa.repository.config.EnableJpaAuditing

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
class CommonAuditingConfig {
    @Bean
    fun auditorAware(): AuditorAware<String> = AuditorAwareImpl()
}
