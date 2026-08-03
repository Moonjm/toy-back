package com.toy.backend.ledger.inbound

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate

/**
 * 결제 시점 환율 조회. **어느 출처를 쓸지만 정하고, 실제 호출은 두 소스가 한다.**
 *
 * - 오늘(또는 날짜 없음) → [NaverFxRateSource] (하나은행 매매기준율, 실시간).
 *   실패하면 [FrankfurterFxRateSource]로 되돌아간다 — 문서화된 API가 아니라 언제든 막힐 수 있다.
 * - 과거 날짜 → [FrankfurterFxRateSource]만. **네이버 계산기에는 날짜 인자가 없다.**
 *
 * 환율은 부가 정보일 뿐이므로 조회 실패는 null로 흡수한다 — 내역 저장을 막지 않는다.
 *
 * **캐시하지 않는다.** 조회는 내역이 들어올 때뿐이라 하루 몇 건이고, 매매기준율은 하루에도
 * 여러 번 바뀐다. 아껴서 얻는 것보다 굳은 값을 쓰는 손해가 크다 — 출처를 바꾼 이유가
 * 0.5~1.1% 차이였다.
 */
@Component
class FxRateClient(
    private val naver: NaverFxRateSource,
    private val frankfurter: FrankfurterFxRateSource,
) {
    /**
     * 1 [currency] 당 원화(KRW) 환율. 미지원 통화·실패 시 null.
     * [date]를 주면 해당 날짜 고시환율을 조회한다(null·오늘 이후는 실시간 — 아직 고시 전이므로).
     */
    fun rateToKrw(
        currency: String,
        date: LocalDate? = null,
    ): BigDecimal? {
        val upper = currency.uppercase()
        if (upper == "KRW") return null
        val historicalDate = date?.takeIf { it.isBefore(LocalDate.now()) }
        return if (historicalDate != null) {
            frankfurter.rateToKrw(upper, historicalDate)
        } else {
            naver.rateToKrw(upper) ?: frankfurter.rateToKrw(upper, null)
        }
    }
}
