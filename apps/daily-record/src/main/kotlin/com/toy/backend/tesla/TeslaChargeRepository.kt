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
}

data class ChargeRow(
    val id: Long,
    val startDateUtc: LocalDateTime,
    val endDateUtc: LocalDateTime,
    val durationMin: Int?,
    val locationName: String?,
    val energyAddedKwh: BigDecimal?,
    val startBatteryLevel: Int?,
    val endBatteryLevel: Int?,
    val cost: BigDecimal?,
)

data class ChargeSummaryRow(
    val count: Int,
    val totalEnergyAddedKwh: BigDecimal?,
    val totalCost: BigDecimal?,
)
