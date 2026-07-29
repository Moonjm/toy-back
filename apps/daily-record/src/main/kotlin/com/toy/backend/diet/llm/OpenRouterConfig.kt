package com.toy.backend.diet.llm

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Duration

/**
 * API 키가 있을 때만 클라이언트를 등록한다. 키 없이 로컬을 띄우면 인식 요청만
 * `LLM_UNAVAILABLE`(503)로 막히고 프로필·확정·점수·집계는 전부 정상 동작한다.
 *
 * **`@ConditionalOnProperty`를 쓰면 안 된다.** `api-key: ${OPENROUTER_API_KEY:}`는 환경변수가
 * 없어도 프로퍼티가 빈 문자열로 *존재*하고, `havingValue`를 비워 둔 `ConditionalOnProperty`는
 * "존재하고 false가 아니면 참"이라 항상 매칭된다. 값이 비었는지를 봐야 하므로 SpEL을 쓴다.
 */
@Configuration
@ConditionalOnExpression("'\${openrouter.api-key:}'.trim().length() > 0")
class OpenRouterConfig(
    private val properties: OpenRouterProperties,
) {
    @Bean
    fun openRouterClient(): OpenRouterClient = OpenRouterClient(properties, openRouterWebClient())

    /**
     * 공휴일 API용 `webClientBuilder`(응답 타임아웃 10초)를 재사용하지 않는다 —
     * 이미지 인식은 수십 초가 걸릴 수 있어 타임아웃 요구가 다르다.
     */
    private fun openRouterWebClient(): WebClient =
        WebClient
            .builder()
            .baseUrl(properties.baseUrl)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${properties.apiKey}")
            .clientConnector(
                ReactorClientHttpConnector(
                    HttpClient.create().responseTimeout(Duration.ofSeconds(properties.timeoutSeconds)),
                ),
            ).codecs { it.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES) }
            .build()

    companion object {
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    }
}
