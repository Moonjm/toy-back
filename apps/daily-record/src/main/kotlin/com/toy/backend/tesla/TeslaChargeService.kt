package com.toy.backend.tesla

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import org.springframework.stereotype.Service
import java.math.BigDecimal

/**
 * **`@Transactional`을 붙이지 않는다.** 기본 트랜잭션 매니저는 daily-record 커넥션의 것이라
 * TeslaMate 쪽 SQL에 아무 효력이 없다. 있는 것처럼 보이는 경계가 없는 것보다 나쁘다.
 * TeslaMate 쓰기는 UPDATE 한 건뿐이라 autocommit으로 충분하다.
 */
@Service
class TeslaChargeService(
    private val repository: TeslaChargeRepository,
) {
    fun detail(id: Long): TeslaChargeDetailResponse {
        val row = repository.findDetail(id) ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        val stats = repository.findChargeStats(id)
        return TeslaChargeDetailResponse(
            id = row.id,
            startedAt = TeslaTime.toKst(row.startDateUtc),
            endedAt = TeslaTime.toKst(row.endDateUtc),
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
     * 영향 행 수로 404를 판정한다 — SELECT로 존재를 확인하고 UPDATE 하는 것보다 왕복이 하나 적고
     * 결과도 같다. 진행 중인 충전은 리포지토리 SQL의 `end_date IS NOT NULL`에 걸려 0이 된다.
     * 그것을 허용하면 TeslaMate가 세션을 마감하며 지오펜스 요금으로 cost를 덮어써 값이 조용히 사라진다.
     */
    fun updateCost(
        id: Long,
        request: ChargeCostRequest,
    ) {
        if (repository.updateCost(id, request.cost) == 0) {
            throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        }
    }

    fun missingCost(limit: Int): MissingCostResponse {
        if (limit !in MIN_LIMIT..MAX_LIMIT) {
            throw CustomException(ErrorCode.INVALID_REQUEST, "limit은 $MIN_LIMIT..$MAX_LIMIT 사이여야 합니다")
        }
        return MissingCostResponse(
            totalCount = repository.countMissingCost(),
            items = repository.findMissingCost(limit).map { it.toItem() },
        )
    }

    /**
     * 쿼리 한 번이 전부다. **완속을 SQL로 따로 세지 않고 합계에서 급속을 뺀다** —
     * 그래야 `급속 + 완속 = 합계`가 어긋날 길이 없고, `charging_processes`를 두 번 훑지도 않는다.
     */
    fun totals(): TeslaChargeTotalsResponse {
        val row = repository.findTotals()
        return TeslaChargeTotalsResponse(
            chargeCount = row.chargeCount,
            energyAddedKwh = row.energyAddedKwh,
            energyUsedKwh = row.energyUsedKwh,
            cost = row.cost,
            costMissingCount = row.costMissingCount,
            costMissingEnergyUsedKwh = row.costMissingEnergyUsedKwh,
            firstChargedAt = row.firstChargedUtc?.let { TeslaTime.toKst(it).toLocalDate() },
            fast =
                ChargeTotalsBreakdown(
                    chargeCount = row.fastChargeCount,
                    energyAddedKwh = row.fastEnergyAddedKwh,
                    energyUsedKwh = row.fastEnergyUsedKwh,
                    cost = row.fastCost,
                    costMissingCount = row.fastCostMissingCount,
                    costMissingEnergyUsedKwh = row.fastCostMissingEnergyUsedKwh,
                ),
            slow =
                ChargeTotalsBreakdown(
                    chargeCount = row.chargeCount - row.fastChargeCount,
                    energyAddedKwh = minus(row.energyAddedKwh, row.fastEnergyAddedKwh),
                    energyUsedKwh = minus(row.energyUsedKwh, row.fastEnergyUsedKwh),
                    cost = minus(row.cost, row.fastCost),
                    costMissingCount = row.costMissingCount - row.fastCostMissingCount,
                    costMissingEnergyUsedKwh = minus(row.costMissingEnergyUsedKwh, row.fastCostMissingEnergyUsedKwh),
                ),
        )
    }

    /**
     * **존재 확인이 먼저다.** `findCurve`가 빈 리스트를 주는 이유가 둘이라
     * (「없는 id·진행 중」과 「샘플이 없는 세션」) 그것만으로는 404를 가릴 수 없다.
     */
    fun curve(id: Long): TeslaChargeCurveResponse {
        if (!repository.existsCompleted(id)) {
            throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        }
        return TeslaChargeCurveResponse(
            samples =
                repository.findCurve(id).map {
                    ChargeCurveSample(
                        at = TeslaTime.toKst(it.dateUtc),
                        powerKw = it.powerKw,
                        batteryLevel = it.batteryLevel,
                    )
                },
        )
    }

    /**
     * 합계 − 급속. **합계가 null이면 결과도 null이다** — 그 구간에 기록이 없다는 뜻이지 0이 아니다.
     * 급속만 null인 경우는 「합계는 있는데 급속이 없다」이므로 합계를 그대로 돌려준다.
     */
    private fun minus(
        total: BigDecimal?,
        fast: BigDecimal?,
    ): BigDecimal? = total?.subtract(fast ?: BigDecimal.ZERO)

    companion object {
        private const val MIN_LIMIT = 1
        private const val MAX_LIMIT = 200
    }
}

internal fun ChargeRow.toItem() =
    ChargeListItem(
        id = id,
        startedAt = TeslaTime.toKst(startDateUtc),
        endedAt = TeslaTime.toKst(endDateUtc),
        durationMin = durationMin,
        locationName = locationName,
        energyAddedKwh = energyAddedKwh,
        energyUsedKwh = energyUsedKwh,
        startBatteryLevel = startBatteryLevel,
        endBatteryLevel = endBatteryLevel,
        cost = cost,
    )
