package com.toy.backend.tesla

import java.math.BigDecimal
import java.time.LocalDateTime

data class TeslaChargeListResponse(
    val summary: ChargeSummary,
    val items: List<ChargeListItem>,
)

/** 집계는 SQL이 계산한다 — 목록을 순회해 더하지 않는다. */
data class ChargeSummary(
    val count: Int,
    val totalEnergyAddedKwh: BigDecimal?,
    val totalCost: BigDecimal?,
)

data class ChargeListItem(
    val id: Long,
    /** KST. TeslaMate는 UTC로 저장한다. */
    val startedAt: LocalDateTime,
    val endedAt: LocalDateTime,
    val durationMin: Int?,
    /** 지오펜스 이름이 있으면 그것, 없으면 주소. 둘 다 없으면 null. */
    val locationName: String?,
    val energyAddedKwh: BigDecimal?,
    val startBatteryLevel: Int?,
    val endBatteryLevel: Int?,
    val cost: BigDecimal?,
)
