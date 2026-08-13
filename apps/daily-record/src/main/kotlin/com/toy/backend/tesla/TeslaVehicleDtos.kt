package com.toy.backend.tesla

import java.math.BigDecimal
import java.time.YearMonth

/**
 * 화면이 하나라 목록과 합계를 한 응답에 싣는다. 둘로 나누면 같은 화면이 두 번 부르고,
 * 그중 하나는 반드시 다른 하나를 기다린다.
 */
data class TeslaSummaryResponse(
    val month: MonthlyStat,
    val previous: MonthlyStat,
    /** 기준 달 포함 거슬러 12개월, 오래된 것부터. 데이터가 없는 달도 자리를 채운다. */
    val trend: List<MonthlyStat>,
    val charges: List<ChargeListItem>,
)

/**
 * 그 달에 기록이 없는 필드는 **0이 아니라 null**이다. 0은 「안 탔다」는 뜻이 되어
 * 「기록이 없다」와 구분되지 않는다.
 *
 * km당 비용·전비는 서버가 계산하지 않는다 — 분모가 0이거나 null일 때의 처리를 서버가
 * 정해 버리면 화면이 그것을 따라야 한다.
 */
data class MonthlyStat(
    val yearMonth: YearMonth,
    val distanceKm: BigDecimal?,
    val drivingMin: Int?,
    val driveCount: Int?,
    val energyAddedKwh: BigDecimal?,
    val energyUsedKwh: BigDecimal?,
    val cost: BigDecimal?,
    val chargeCount: Int?,
)
