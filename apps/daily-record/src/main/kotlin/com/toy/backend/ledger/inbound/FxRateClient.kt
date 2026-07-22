package com.toy.backend.ledger.inbound

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

/**
 * 결제 시점 환율 조회 — Frankfurter(ECB 고시환율, 무료·키 불필요).
 * 특정 통화를 하드코딩하지 않고 ECB가 고시하는 30여 통화(USD·JPY·EUR·CNY·GBP 등)를 그대로 커버한다.
 *
 * 환율은 부가 정보일 뿐이므로 조회 실패는 null로 흡수한다 — 내역 저장을 막지 않는다.
 * 같은 통화는 하루 한 번만 조회하도록 (통화, 날짜) 단위로 캐시한다(ECB는 일 단위 고시).
 */
@Component
class FxRateClient(
    webClientBuilder: WebClient.Builder,
) {
    private val webClient = webClientBuilder.baseUrl(BASE_URL).build()
    private val cache = ConcurrentHashMap<String, BigDecimal>()

    /** 1 [currency] 당 원화(KRW) 환율. 미지원 통화·실패 시 null. */
    fun rateToKrw(currency: String): BigDecimal? {
        val upper = currency.uppercase()
        if (upper == "KRW") return null
        val key = "$upper:${LocalDate.now()}"
        cache[key]?.let { return it }
        val rate = fetch(upper) ?: return null
        cache[key] = rate
        return rate
    }

    private fun fetch(currency: String): BigDecimal? =
        runCatching {
            // v2 단일 통화쌍 응답: {"date":"2026-07-22","base":"JPY","quote":"KRW","rate":9.0854}
            webClient
                .get()
                .uri("/v2/rate/{base}/KRW", currency)
                .retrieve()
                .bodyToMono<JsonNode>()
                .block(TIMEOUT)
                ?.path("rate")
                ?.takeIf { it.isNumber }
                ?.decimalValue()
        }.onFailure {
            log.warn { "환율 조회 실패(내역은 환율 없이 저장): currency=$currency, cause=${it.message}" }
        }.getOrNull()

    companion object {
        private const val BASE_URL = "https://api.frankfurter.dev"
        private val TIMEOUT = Duration.ofSeconds(3)
    }
}
