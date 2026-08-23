package com.toy.backend.dispatch.llm

import com.toy.backend.dispatch.DispatchProperties
import com.toy.backend.vision.VisionProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient

@Configuration
@EnableConfigurationProperties(DispatchProperties::class)
class DispatchVisionConfig {
    /** WebClient는 `VisionConfig`가 만든 것을 함께 쓴다. 키·타임아웃이 관리비와 같아졌다. */
    @Bean
    fun dispatchVisionClient(
        properties: VisionProperties,
        visionWebClient: WebClient,
    ): DispatchVisionClient = DispatchVisionClient(properties, visionWebClient)
}
