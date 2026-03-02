package com.toy.backend.holidays

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Configuration
class HolidayConfig {
    @Bean
    fun virtualThreadExecutor(): ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
}
