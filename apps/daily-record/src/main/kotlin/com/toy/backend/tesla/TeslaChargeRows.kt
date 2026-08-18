/*
 * TeslaMate 조회의 **행 타입**들. 리포지토리가 돌려주고 서비스가 받는다.
 *
 * **여기 있는 시각은 전부 UTC다** — TeslaMate가 타임존 없는 `timestamp` 컬럼에 UTC 값을 넣기
 * 때문이다. KST로 되돌리는 것은 서비스의 일이고, KST를 담는 것은 `TeslaChargeDtos.kt`의
 * 응답 타입이다. 두 파일을 갈라 둔 이유가 그 경계다.
 */

package com.toy.backend.tesla

import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.YearMonth

data class ChargeRow(
    val id: Long,
    val startDateUtc: LocalDateTime,
    val endDateUtc: LocalDateTime,
    val durationMin: Int?,
    val locationName: String?,
    val energyAddedKwh: BigDecimal?,
    /** 벽에서 뽑아쓴 양. 구버전 데이터에서 null일 수 있다. */
    val energyUsedKwh: BigDecimal?,
    val startBatteryLevel: Int?,
    val endBatteryLevel: Int?,
    val cost: BigDecimal?,
)

data class ChargeDetailRow(
    val id: Long,
    val startDateUtc: LocalDateTime,
    val endDateUtc: LocalDateTime,
    val durationMin: Int?,
    val energyAddedKwh: BigDecimal?,
    val energyUsedKwh: BigDecimal?,
    val startBatteryLevel: Int?,
    val endBatteryLevel: Int?,
    val startRatedRangeKm: BigDecimal?,
    val endRatedRangeKm: BigDecimal?,
    val outsideTempAvg: BigDecimal?,
    val geofenceName: String?,
    val address: String?,
    val cost: BigDecimal?,
)

data class ChargeStatsRow(
    val maxPowerKw: Int?,
    val avgPowerKw: BigDecimal?,
    val fastCharger: Boolean?,
    val fastChargerBrand: String?,
    val fastChargerType: String?,
)

data class ChargeMonthRow(
    val month: YearMonth,
    val count: Int,
    val energyAddedKwh: BigDecimal?,
    val energyUsedKwh: BigDecimal?,
    val cost: BigDecimal?,
)

/**
 * 전 기간 충전 누적. **한 쿼리가 합계와 급속분을 함께 낸다** — 완속은 서비스가 뺄셈으로 만든다.
 * 그래야 `급속 + 완속 = 합계`가 어긋날 수 없다.
 *
 * **모집단은 `end_date IS NOT NULL AND (charge_energy_added > 0 OR cost IS NOT NULL)`이다.**
 * 케이블만 꽂았다 뺀 축퇴 세션(SoC 그대로, kWh 0)을 빼되, TeslaMate가 데이터를 잃고 금액만
 * 남은 행은 남긴다 — 그 돈은 실제로 나갔다.
 *
 * 합계 필드가 nullable인 것은 `SUM`이 빈 집합에 null을 주기 때문이다. 개수는 `COUNT`라 non-null이다.
 */
data class ChargeTotalsRow(
    val chargeCount: Int,
    val energyAddedKwh: BigDecimal?,
    val energyUsedKwh: BigDecimal?,
    /** `SUM`이 null을 건너뛰므로 **실제로 낸 돈**이다. 미입력분은 여기 없다. */
    val cost: BigDecimal?,
    /**
     * 금액이 비어 있는 건수. 실측(2026-08-18)으로 이 차량은 35건이고 급속에 몰려 있는데,
     * **서버는 그것을 「무료」라고 부르지 않는다** — DB에 기록된 것은 「금액 없음」이지 「0원」이
     * 아니다. 해석은 앱이 붙인다.
     */
    val costMissingCount: Int,
    /**
     * 금액이 빈 세션들의 `charge_energy_used` 합. **앱이 kWh당 단가를 옳게 내는 분모다** —
     * `cost ÷ (energyUsedKwh − costMissingEnergyUsedKwh)`. 이 값이 없으면 단가가 5.6% 낮게 나온다.
     */
    val costMissingEnergyUsedKwh: BigDecimal?,
    val firstChargedUtc: LocalDateTime?,
    val fastChargeCount: Int,
    val fastEnergyAddedKwh: BigDecimal?,
    val fastEnergyUsedKwh: BigDecimal?,
    val fastCost: BigDecimal?,
    val fastCostMissingCount: Int,
    val fastCostMissingEnergyUsedKwh: BigDecimal?,
)

/**
 * 충전 곡선의 샘플 하나. **줄이지 않고 그대로 낸다** — 완속 세션은 1,700개까지 간다.
 * 「어느 점을 버릴지」를 서버가 정하면 화면이 그것을 따라야 한다.
 */
data class ChargeCurveSampleRow(
    val dateUtc: LocalDateTime,
    val powerKw: Int?,
    val batteryLevel: Int?,
)
