package com.toy.backend.holidays

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient

@Configuration
class HolidayConfig {
    @Bean
    fun webClientBuilder(): WebClient.Builder = WebClient.builder()
}
