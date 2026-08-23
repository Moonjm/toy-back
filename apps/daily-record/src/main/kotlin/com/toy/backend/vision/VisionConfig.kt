package com.toy.backend.vision

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
@EnableConfigurationProperties(VisionProperties::class)
class VisionConfig {
    /** 배차표·관리비 판독이 공유한다. 설정이 같아진 뒤로 두 벌을 둘 이유가 없다. */
    @Bean
    fun visionWebClient(properties: VisionProperties): WebClient {
        val httpClient =
            HttpClient
                .create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .doOnConnected {
                    it.addHandlerLast(ReadTimeoutHandler(properties.timeoutSeconds, TimeUnit.SECONDS))
                }

        return WebClient
            .builder()
            .baseUrl(properties.baseUrl)
            .defaultHeader("Authorization", "Bearer ${properties.apiKey}")
            // 사진 한 장이 base64로 수백 KB다. 기본 256KB 버퍼로는 응답 처리 중 터질 수 있다.
            .codecs { it.defaultCodecs().maxInMemorySize(16 * 1024 * 1024) }
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
    }
}
