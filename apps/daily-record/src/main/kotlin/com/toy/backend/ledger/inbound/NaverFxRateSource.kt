package com.toy.backend.ledger.inbound

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.math.MathContext
import java.time.Duration

private val log = KotlinLogging.logger {}

/**
 * 네이버 검색 환율 계산기(하나은행 고시 매매기준율). **지금 시각 환율만 준다 — 과거 조회가 없다.**
 *
 * 왜 바꿨나 — 기존 Frankfurter(ECB)와 실측 차이가 작지 않다. 2026-08-03 기준
 * USD −0.55% · JPY **+1.14%** · EUR −0.49% · CNY −0.63% · GBP −0.44%. ECB는 하루 한 번
 * 유로 기준으로 고시하고 그걸 교차환산해 원화로 오는 값이라, 국내 카드 결제에 실제로 적용되는
 * 매매기준율과 벌어진다. ¥50,000짜리 결제면 1.14%가 5,000원 넘는 차이가 된다.
 *
 * **감수하는 것 — 네이버가 `robots.txt`에 `Disallow: /`를 걸어 두었다.** 문서화된 공개 API가
 * 아니라 모바일 검색 화면이 쓰는 내부 엔드포인트이므로, 예고 없이 형식이 바뀌거나 막힐 수 있다.
 * 그래서 **이 경로가 죽어도 기록이 멈추지 않도록** 호출자(`FxRateClient`)가 Frankfurter로
 * 되돌아간다. 브라우저인 척하는 `User-Agent`는 보내지 않는다.
 */
@Component
class NaverFxRateSource(
    webClientBuilder: WebClient.Builder,
) {
    private val webClient = webClientBuilder.baseUrl(BASE_URL).build()

    /** 1 [currency] 당 원화. 실패·미지원 통화면 null. */
    fun rateToKrw(currency: String): BigDecimal? =
        runCatching {
            webClient
                .get()
                .uri { builder ->
                    builder
                        .path(PATH)
                        .queryParam("key", "calculator")
                        .queryParam("pkid", "141")
                        .queryParam("q", "환율")
                        .queryParam("where", "m")
                        // 하나은행(keb) 매매기준율(standardUnit). `u8`은 화면 표시 방향일 뿐이다.
                        .queryParam("u1", "keb")
                        .queryParam("u6", "standardUnit")
                        .queryParam("u7", "0")
                        .queryParam("u3", currency)
                        .queryParam("u4", "KRW")
                        .queryParam("u8", "down")
                        .queryParam("u2", UNITS)
                        .build()
                }.retrieve()
                .bodyToMono<JsonNode>()
                .block(TIMEOUT)
                ?.let { NaverFxRateParser.parse(it, UNITS) }
        }.onFailure {
            log.warn { "네이버 환율 조회 실패(Frankfurter로 넘어간다): currency=$currency, cause=${it.message}" }
        }.getOrNull()

    companion object {
        private const val BASE_URL = "https://m.search.naver.com"
        private const val PATH = "/p/csearch/content/qapirender.nhn"

        /**
         * 몇 단위를 물어볼지. **1로 물으면 저액 통화가 뭉개진다** — 응답이 소수 둘째 자리까지라
         * VND가 `0.05`로 와서 실제 `0.0544` 대비 8% 틀린다(IDR은 0.5%).
         *
         * 100이면 재 본 통화 전부가 온전하게 나온다 — USD 1428.2 · JPY 9.102 · VND 0.0544 ·
         * IDR 0.0796 · EUR 1645.5. 더 키워도 나아지지 않는다(유효숫자가 6자리에서 잘린다).
         *
         * **줄이면 조용히 틀린 값이 들어간다** — 어디서도 예외가 안 난다. `NaverFxRateParserTest`가
         * 이 값을 지킨다.
         */
        internal const val UNITS = 100

        private val TIMEOUT = Duration.ofSeconds(3)
    }
}

/**
 * 응답에서 환율을 꺼낸다. **형식이 바뀌면 여기가 먼저 깨지도록 떼어 두었다** — 문서화된 API가
 * 아니라서 형식 변화가 이 연동의 가장 큰 위험이다.
 *
 * ```json
 * { "pkid": 141, "count": 1,
 *   "country": [ {"value":"100","currencyUnit":"엔"}, {"value":"910.19","currencyUnit":"원"} ] }
 * ```
 *
 * 모르는 통화(`u3=XYZ`)에는 `{}`가 온다 — `country`가 없으면 null이다.
 */
object NaverFxRateParser {
    fun parse(
        body: JsonNode,
        units: Int,
    ): BigDecimal? {
        val country = body.path("country")
        // [0]은 물어본 쪽(100 엔), [1]이 원화 환산액이다. 둘 다 있어야 형식이 맞는 것이다.
        if (!country.isArray || country.size() != 2) return null
        val krw =
            country[1]
                .path("value")
                .asString()
                ?.replace(",", "")
                ?.trim() ?: return null
        val total = krw.toBigDecimalOrNull() ?: return null
        // 0 이하는 환율일 수 없다 — 형식이 바뀌어 엉뚱한 칸을 읽은 것이다.
        if (total <= BigDecimal.ZERO) return null
        return total.divide(units.toBigDecimal(), MathContext.DECIMAL64)
    }
}
