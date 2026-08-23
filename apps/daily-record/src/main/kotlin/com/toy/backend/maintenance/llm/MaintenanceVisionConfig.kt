package com.toy.backend.maintenance.llm

import com.toy.backend.vision.VisionProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient

@Configuration
class MaintenanceVisionConfig {
    /** WebClient는 `VisionConfig`가 만든 것을 함께 쓴다. 키·타임아웃이 배차와 같아졌다. */
    @Bean
    fun maintenanceVisionClient(
        properties: VisionProperties,
        visionWebClient: WebClient,
    ): MaintenanceVisionClient = MaintenanceVisionClient(properties, visionWebClient)
}
