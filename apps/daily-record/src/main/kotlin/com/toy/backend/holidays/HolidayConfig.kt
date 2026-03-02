package com.toy.backend.holidays

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Configuration
class HolidayConfig {
    @Bean
    fun virtualThreadExecutor(): ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    @Bean
    fun restClientBuilder(): RestClient.Builder = RestClient.builder()
}
