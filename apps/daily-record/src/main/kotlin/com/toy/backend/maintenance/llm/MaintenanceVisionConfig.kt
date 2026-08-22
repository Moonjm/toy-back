package com.toy.backend.maintenance.llm

import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.util.concurrent.TimeUnit

@Configuration
@EnableConfigurationProperties(MaintenanceVisionProperties::class)
class MaintenanceVisionConfig {
    @Bean
    fun maintenanceVisionClient(properties: MaintenanceVisionProperties): MaintenanceVisionClient {
        val httpClient =
            HttpClient
                .create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .doOnConnected {
                    it.addHandlerLast(ReadTimeoutHandler(properties.timeoutSeconds, TimeUnit.SECONDS))
                }

        val webClient =
            WebClient
                .builder()
                .baseUrl(properties.baseUrl)
                .defaultHeader("Authorization", "Bearer ${properties.apiKey}")
                // 사진 한 장이 base64로 수백 KB다. 기본 256KB 버퍼로는 터질 수 있다.
                .codecs { it.defaultCodecs().maxInMemorySize(16 * 1024 * 1024) }
                .clientConnector(ReactorClientHttpConnector(httpClient))
                .build()

        return MaintenanceVisionClient(properties, webClient)
    }
}
