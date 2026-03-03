package com.toy.backend.holidays

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Duration

@Configuration
class HolidayConfig {
    @Bean
    fun webClientBuilder(): WebClient.Builder =
        WebClient
            .builder()
            .clientConnector(
                ReactorClientHttpConnector(
                    HttpClient.create().responseTimeout(Duration.ofSeconds(10)),
                ),
            )
}
