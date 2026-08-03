package com.toy.backend.ledger.inbound

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate

private val log = KotlinLogging.logger {}

/**
 * Frankfurter(ECB 고시환율, 무료·키 불필요). 특정 통화를 하드코딩하지 않고 ECB가 고시하는
 * 30여 통화(USD·JPY·EUR·CNY·GBP 등)를 그대로 커버한다.
 *
 * **두 가지 역할로 남는다** — ① 과거 날짜 조회(네이버 계산기에는 날짜 인자가 없다),
 * ② 네이버가 막히거나 형식이 바뀌었을 때의 대비책.
 *
 * 국내 매매기준율과는 실측으로 USD −0.55% · JPY +1.14% 차이가 난다. 유로 기준 하루 한 번
 * 고시를 교차환산한 값이라 그렇다 — 그래서 오늘 환율은 `NaverFxRateSource`를 먼저 쓴다.
 */
@Component
class FrankfurterFxRateSource(
    webClientBuilder: WebClient.Builder,
) {
    private val webClient = webClientBuilder.baseUrl(BASE_URL).build()

    /** 1 [currency] 당 원화. [date]가 null이면 최신 고시. 실패·미지원 통화면 null. */
    fun rateToKrw(
        currency: String,
        date: LocalDate?,
    ): BigDecimal? =
        runCatching {
            // v2 단일 통화쌍 응답: {"date":"2026-07-22","base":"JPY","quote":"KRW","rate":9.0854}
            // 과거 고시는 ?date=YYYY-MM-DD (주말·휴일은 직전 영업일 고시로 응답)
            webClient
                .get()
                .uri { builder ->
                    builder
                        .path("/v2/rate/{base}/KRW")
                        .apply { if (date != null) queryParam("date", date.toString()) }
                        .build(currency)
                }.retrieve()
                .bodyToMono<JsonNode>()
                .block(TIMEOUT)
                ?.path("rate")
                ?.takeIf { it.isNumber }
                ?.decimalValue()
        }.onFailure {
            log.warn { "Frankfurter 환율 조회 실패: currency=$currency, date=$date, cause=${it.message}" }
        }.getOrNull()

    companion object {
        private const val BASE_URL = "https://api.frankfurter.dev"
        private val TIMEOUT = Duration.ofSeconds(3)
    }
}
