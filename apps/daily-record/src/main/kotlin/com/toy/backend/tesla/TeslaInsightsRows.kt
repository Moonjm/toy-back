/*
 * `/tesla/insights`·`/tesla/battery-window`의 행 타입들. 리포지토리가 돌려주고 서비스가
 * 받는다.
 *
 * **여기 있는 시각은 전부 UTC다** — TeslaMate가 타임존 없는 `timestamp` 컬럼에 UTC 값을 넣기
 * 때문이다. KST로 되돌리는 것은 서비스의 일이고, KST를 담는 것은 `TeslaInsightsDtos.kt`의
 * 응답 타입이다. `TeslaVehicleRows.kt`와 같은 경계다.
 */

package com.toy.backend.tesla

import java.math.BigDecimal
import java.time.YearMonth

/**
 * 한 달치 주행 집계. `/tesla/summary`의 `DriveMonthRow`와 모집단·경계·반올림이 같고
 * `ratedRangeUsedKm` 하나가 더 있다 — 효율 추세의 분모 재료다. **두 응답의 같은 달 숫자가
 * 달라지면 앱의 어느 화면이 맞는지 알 수 없어진다.**
 *
 * 행이 온 달만 온다. 빈 달의 자리를 채우는 것은 서비스가 한다.
 */
data class InsightsDriveMonthRow(
    val month: YearMonth,
    val driveCount: Int,
    val distanceKm: BigDecimal,
    val drivingMin: Int?,
    /** `start_rated_range_km − end_rated_range_km`의 합. **음수 주행은 0으로 보고 더한다.** */
    val ratedRangeUsedKm: BigDecimal,
)

/**
 * 한 달치 충전 집계. `/tesla/summary`의 `ChargeMonthRow`에 `chargingMin`이 더 있다 —
 * 정지 시간의 뺄셈에 쓴다.
 *
 * `cost`만 nullable이다. 금액 미입력 충전만 있는 달이면 `SUM`이 null이고, 그때 **0이 아니라
 * null이 사실이다**(「0원 냈다」가 아니라 「얼마인지 모른다」).
 */
data class InsightsChargeMonthRow(
    val month: YearMonth,
    val chargeCount: Int,
    val energyAddedKwh: BigDecimal,
    val energyUsedKwh: BigDecimal,
    val cost: BigDecimal?,
    val chargingMin: Int,
)

/**
 * 한 달치 팬텀 드레인. 연속한 두 주행 사이에 충전이 하나도 없는 구간만 세고, 그 구간의
 * `이전 주행 end_rated_range_km − 다음 주행 start_rated_range_km`를 더한 것이 `ratedKm`이다.
 *
 * **음수 구간을 0으로 자르지 않는다** — 충전 기록 없이 정격거리가 늘어난 구간이 실측으로
 * 3,960:628 섞여 있다(BMS 재보정 등). 자르면 합이 위로 편향된다(월 합은 실측 75~169km로
 * 어차피 양수다).
 *
 * `samples`를 함께 내는 이유: 표본 3건짜리 달과 90건짜리 달이 응답에서 같아 보이면 안 된다.
 */
data class ParkDrainMonthRow(
    val month: YearMonth,
    val ratedKm: BigDecimal,
    val samples: Int,
)
