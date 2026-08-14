package com.toy.backend.tesla

import java.math.BigDecimal
import java.time.LocalDateTime
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

/**
 * `positions`의 최신 1행 + `states`의 열린 행. **값과 `asOf`를 항상 함께 낸다** —
 * 주차 중에는 위치가 뜸하게 쌓여 몇 시간 전 값일 수 있고, 시각 없이 배터리 %만 보면
 * 지금 값으로 읽힌다.
 *
 * 좌표를 싣지 않는다 — 생활 동선이 그대로 드러나고, 앱이 지도를 그리지 않는다.
 */
data class TeslaStatusResponse(
    /** 위치 행의 시각(KST). 기록이 하나도 없으면 null이다. */
    val asOf: LocalDateTime?,
    /**
     * `charging`·`driving`·`online`·`offline`·`asleep`.
     * 앞의 둘은 열린 행에서 파생한 것이고, 나머지는 `states`의 값 그대로다.
     */
    val state: String?,
    /** `states`의 열린 행이 시작된 시각(KST). 파생 상태에는 해당하지 않아 null일 수 있다. */
    val stateSince: LocalDateTime?,
    val batteryLevel: Int?,
    val usableBatteryLevel: Int?,
    val ratedRangeKm: BigDecimal?,
    val estRangeKm: BigDecimal?,
    val odometerKm: Double?,
    val insideTempC: BigDecimal?,
    val outsideTempC: BigDecimal?,
    val climateOn: Boolean?,
    /** 반경 안의 지오펜스 이름. 없으면 null이다(주소는 내지 않는다). */
    val locationName: String?,
    /** 위치 기록이 없으면 통째로 null이다. */
    val tpmsBar: TpmsBar?,
)

/** TeslaMate 저장 단위인 bar 그대로. psi 병기는 앱이 한다. */
data class TpmsBar(
    val fl: BigDecimal?,
    val fr: BigDecimal?,
    val rl: BigDecimal?,
    val rr: BigDecimal?,
)
