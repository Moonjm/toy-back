package com.toy.backend.tesla

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * `@Transactional`을 붙이지 않는다 — 기본 트랜잭션 매니저는 daily-record 커넥션의 것이라
 * TeslaMate 쪽 SQL에 효력이 없다.
 *
 * **리포지토리를 둘 주입받는다.** 온도·시간대·거리·장소·주행통계·전비는 `TeslaVehicleRepository`의
 * 것을 그대로 쓴다 — SQL을 복사하면 버킷 경계가 두 곳에 생긴다.
 */
@Service
class TeslaInsightsService(
    private val insightsRepository: TeslaInsightsRepository,
    private val vehicleRepository: TeslaVehicleRepository,
) {
    fun insights(months: Int): TeslaInsightsResponse {
        val window = windowOf(months)
        val temperatures = vehicleRepository.driveTemperatureBuckets(window.startUtc, window.endUtc).associateBy { it.bucket }
        val distances = vehicleRepository.driveDistanceBuckets(window.startUtc, window.endUtc).associateBy { it.bucket }
        val stats = vehicleRepository.driveStats()
        val drives = insightsRepository.driveMonthly(window.startUtc, window.endUtc).associateBy { it.month }
        val charges = insightsRepository.chargeMonthly(window.startUtc, window.endUtc).associateBy { it.month }
        val parkDrains = insightsRepository.parkDrainMonthly(window.startUtc, window.endUtc).associateBy { it.month }

        return TeslaInsightsResponse(
            months = months,
            monthly = window.months().map { monthOf(it, window, drives, charges, parkDrains) },
            efficiencyKwhPerKm = vehicleRepository.carEfficiency(),
            temperatureBuckets =
                TeslaBuckets.TEMPERATURE.map { (bucket, bounds) ->
                    val row = temperatures[bucket]
                    TemperatureBucket(
                        fromC = bounds.first,
                        toC = bounds.second,
                        driveCount = row?.driveCount ?: 0,
                        distanceKm = row?.distanceKm ?: BigDecimal.ZERO,
                        ratedRangeUsedKm = row?.ratedRangeUsedKm ?: BigDecimal.ZERO,
                    )
                },
            driveTimes =
                vehicleRepository.driveTimes(window.startUtc, window.endUtc).map {
                    DriveTime(weekday = it.weekday, hour = it.hour, count = it.count)
                },
            distanceBuckets =
                TeslaBuckets.DISTANCE.map { (bucket, bounds) ->
                    val row = distances[bucket]
                    DistanceBucket(
                        fromKm = bounds.first,
                        toKm = bounds.second,
                        driveCount = row?.driveCount ?: 0,
                        distanceKm = row?.distanceKm ?: BigDecimal.ZERO,
                    )
                },
            places =
                vehicleRepository.drivePlaces(window.startUtc, window.endUtc).map {
                    DrivePlace(name = it.name, driveCount = it.driveCount, distanceKm = it.distanceKm)
                },
            maxSpeedKmh = stats.maxSpeedKmh,
            totalDistanceKm = stats.totalDistanceKm,
            recordedMonths = stats.recordedMonths,
        )
    }

    /**
     * 범위를 한 번 정해 모든 쿼리가 같은 걸 쓴다 — 달에 맞춘 범위라 `monthly` 배열과 기간이
     * 어긋나지 않는다. 끝은 요청 시각이다(달 끝으로 잡으면 진행 중인 달의 정지 시간이 부풀어진다).
     *
     * `months=0`이면 가장 오래된 주행의 달부터다. 주행이 없으면 이번 달 한 칸만 남는다.
     */
    private fun windowOf(months: Int): InsightsWindow {
        if (months !in ALL_MONTHS..MAX_MONTHS) {
            throw CustomException(ErrorCode.INVALID_REQUEST, "months는 $ALL_MONTHS~$MAX_MONTHS 사이여야 합니다(0은 전체 기간)")
        }

        val nowKst = TeslaTime.nowKst()
        val toMonth = YearMonth.from(nowKst)
        val fromMonth =
            if (months == ALL_MONTHS) {
                insightsRepository.firstDriveMonth()?.coerceAtMost(toMonth) ?: toMonth
            } else {
                toMonth.minusMonths((months - 1).toLong())
            }
        val startKst = fromMonth.atDay(1).atStartOfDay()

        return InsightsWindow(
            fromMonth = fromMonth,
            toMonth = toMonth,
            startKst = startKst,
            endKst = nowKst,
            startUtc = TeslaTime.toUtc(startKst),
            endUtc = TeslaTime.toUtc(nowKst),
        )
    }

    /** 한 요청의 조회 범위. KST와 UTC를 함께 든다 — SQL은 UTC를, 뺄셈은 KST를 쓴다. */
    private data class InsightsWindow(
        val fromMonth: YearMonth,
        val toMonth: YearMonth,
        val startKst: LocalDateTime,
        val endKst: LocalDateTime,
        val startUtc: LocalDateTime,
        val endUtc: LocalDateTime,
    )

    /** 범위의 달을 오래된 것부터. **기록이 없는 달도 자리를 지킨다.** */
    private fun InsightsWindow.months(): List<YearMonth> =
        generateSequence(fromMonth) { it.plusMonths(1) }
            .takeWhile { !it.isAfter(toMonth) }
            .toList()

    /**
     * 행 셋을 한 달로 합친다. **서비스가 하는 유일한 산술이 여기 있다** — 정지 시간의 뺄셈이다.
     * 그것을 SQL로 옮기려면 「지금」을 SQL이 알아야 하고, 그러면 테스트가 시각을 못 박을 수 없다.
     */
    private fun monthOf(
        month: YearMonth,
        window: InsightsWindow,
        drives: Map<YearMonth, InsightsDriveMonthRow>,
        charges: Map<YearMonth, InsightsChargeMonthRow>,
        parkDrains: Map<YearMonth, ParkDrainMonthRow>,
    ): InsightsMonth {
        val drive = drives[month]
        val charge = charges[month]
        val parkDrain = parkDrains[month]
        val elapsedMin = TeslaTime.monthElapsedMinutes(month, window.startKst, window.endKst)

        return InsightsMonth(
            yearMonth = month,
            distanceKm = drive?.distanceKm,
            driveCount = drive?.driveCount,
            drivingMin = drive?.drivingMin,
            energyAddedKwh = charge?.energyAddedKwh,
            energyUsedKwh = charge?.energyUsedKwh,
            cost = charge?.cost,
            chargeCount = charge?.chargeCount,
            chargingMin = charge?.chargingMin,
            ratedRangeUsedKm = drive?.ratedRangeUsedKm,
            idleMin = (elapsedMin - (drive?.drivingMin ?: 0) - (charge?.chargingMin ?: 0)).coerceAtLeast(0),
            parkDrainRatedKm = parkDrain?.ratedKm ?: BigDecimal.ZERO,
            parkDrainSamples = parkDrain?.samples ?: 0,
        )
    }

    companion object {
        /** `months`의 범위. **0은 전체 기간**이고 상한 60은 실측 기록 길이(60개월)에서 왔다. */
        const val ALL_MONTHS = 0
        const val MAX_MONTHS = 60
    }
}
