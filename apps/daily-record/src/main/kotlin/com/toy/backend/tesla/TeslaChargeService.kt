package com.toy.backend.tesla

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import org.springframework.stereotype.Service

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
        val cost = request.cost ?: throw CustomException(ErrorCode.INVALID_REQUEST, "cost는 필수입니다")
        if (repository.updateCost(id, cost) == 0) {
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
