package com.toy.backend.tesla

import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * TeslaMate DB 접근. **행 타입의 시각은 전부 UTC다** — TeslaMate가 타임존 없는 timestamp에
 * UTC 값을 넣기 때문이다. KST 변환은 서비스가 한다.
 */
interface TeslaChargeRepository {
    fun findList(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<ChargeRow>

    fun summarize(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): ChargeSummaryRow

    /** 없으면 null. 진행 중(`end_date IS NULL`)인 행도 없는 것으로 본다. */
    fun findDetail(id: Long): ChargeDetailRow?

    /** 샘플이 하나도 없어도 행은 온다 — 모든 필드가 null인 행이다. */
    fun findChargeStats(id: Long): ChargeStatsRow

    /**
     * TeslaMate DB에 쓰는 **유일한** 자리다. 영향 행 수를 돌려준다 —
     * 없는 id와 진행 중인 행이 모두 0이 된다.
     */
    fun updateCost(
        id: Long,
        cost: BigDecimal,
    ): Int
}

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

data class ChargeSummaryRow(
    val count: Int,
    val totalEnergyAddedKwh: BigDecimal?,
    val totalCost: BigDecimal?,
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
