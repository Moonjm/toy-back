package com.toy.backend.tesla

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * **`@Transactional`을 붙이지 않는다.** 기본 트랜잭션 매니저는 daily-record 커넥션의 것이라
 * TeslaMate 쪽 SQL에 아무 효력이 없다. 있는 것처럼 보이는 경계가 없는 것보다 나쁘다.
 * TeslaMate 쓰기는 UPDATE 한 건뿐이라 autocommit으로 충분하다.
 */
@Service
class TeslaChargeService(
    private val repository: TeslaChargeRepository,
) {
    fun list(
        yearMonth: YearMonth?,
        from: LocalDate?,
        to: LocalDate?,
    ): TeslaChargeListResponse {
        val (startUtc, endUtc) = resolveRange(yearMonth, from, to)
        val summary = repository.summarize(startUtc, endUtc)
        val items = repository.findList(startUtc, endUtc).map { it.toItem() }
        return TeslaChargeListResponse(
            summary = ChargeSummary(summary.count, summary.totalEnergyAddedKwh, summary.totalCost),
            items = items,
        )
    }

    fun detail(id: Long): TeslaChargeDetailResponse {
        val row = repository.findDetail(id) ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        val stats = repository.findChargeStats(id)
        return TeslaChargeDetailResponse(
            id = row.id,
            startedAt = toKst(row.startDateUtc),
            endedAt = toKst(row.endDateUtc),
            durationMin = row.durationMin,
            energyAddedKwh = row.energyAddedKwh,
            energyUsedKwh = row.energyUsedKwh,
            startBatteryLevel = row.startBatteryLevel,
            endBatteryLevel = row.endBatteryLevel,
            startRatedRangeKm = row.startRatedRangeKm,
            endRatedRangeKm = row.endRatedRangeKm,
            outsideTempAvg = row.outsideTempAvg,
            geofenceName = row.geofenceName,
            address = row.address,
            cost = row.cost,
            maxPowerKw = stats.maxPowerKw,
            avgPowerKw = stats.avgPowerKw,
            fastCharger = stats.fastCharger,
            fastChargerBrand = stats.fastChargerBrand,
            fastChargerType = stats.fastChargerType,
        )
    }

    /**
     * KST 경계를 UTC로 번역한다. `to`는 **포함**이라 그 다음 날 자정이 상한이 된다.
     * 기본값을 「이번 달」로 채우지 않는다 — 조회 범위가 응답에 실리지 않으므로 서버가 몰래 고른
     * 범위를 호출자가 모른 채 화면에 그리게 된다.
     */
    private fun resolveRange(
        yearMonth: YearMonth?,
        from: LocalDate?,
        to: LocalDate?,
    ): Pair<LocalDateTime, LocalDateTime> {
        if (yearMonth == null && from == null && to == null) {
            throw CustomException(ErrorCode.INVALID_REQUEST, "yearMonth 또는 from·to 중 하나는 필요합니다")
        }
        if (yearMonth != null && (from != null || to != null)) {
            throw CustomException(ErrorCode.INVALID_REQUEST, "yearMonth와 from·to는 함께 보낼 수 없습니다")
        }
        if (yearMonth != null) {
            return toUtc(yearMonth.atDay(1).atStartOfDay()) to
                toUtc(yearMonth.plusMonths(1).atDay(1).atStartOfDay())
        }
        if (from == null || to == null) {
            throw CustomException(ErrorCode.INVALID_REQUEST, "from과 to는 함께 보내야 합니다")
        }
        if (from.isAfter(to)) {
            throw CustomException(ErrorCode.INVALID_REQUEST, "from이 to보다 늦습니다")
        }
        return toUtc(from.atStartOfDay()) to toUtc(to.plusDays(1).atStartOfDay())
    }

    private fun ChargeRow.toItem() =
        ChargeListItem(
            id = id,
            startedAt = toKst(startDateUtc),
            endedAt = toKst(endDateUtc),
            durationMin = durationMin,
            locationName = locationName,
            energyAddedKwh = energyAddedKwh,
            startBatteryLevel = startBatteryLevel,
            endBatteryLevel = endBatteryLevel,
            cost = cost,
        )

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")

        fun toUtc(kst: LocalDateTime): LocalDateTime = kst.atZone(KST).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()

        fun toKst(utc: LocalDateTime): LocalDateTime = utc.atZone(ZoneOffset.UTC).withZoneSameInstant(KST).toLocalDateTime()
    }
}
