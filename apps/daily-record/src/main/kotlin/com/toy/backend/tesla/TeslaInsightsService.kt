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
        val weekdayDrives = insightsRepository.weekdayDrives(window.startUtc, window.endUtc).associateBy { it.weekday }
        val weekdayCharges = insightsRepository.weekdayCharges(window.startUtc, window.endUtc).associateBy { it.weekday }
        val spans = TeslaTime.weekdaySpans(window.startKst, window.endKst)
        val speeds = insightsRepository.speedBuckets(window.startUtc, window.endUtc).associateBy { it.bucket }
        val speedEnergies = insightsRepository.speedEnergyBuckets(window.startUtc, window.endUtc).associateBy { it.bucket }
        val chargeLevels = insightsRepository.chargeLevelBuckets(window.startUtc, window.endUtc).associateBy { it.bucket }
        val records = insightsRepository.driveRecords().associateBy { it.kind }

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
            weekday =
                (1..7).map { weekday ->
                    val drive = weekdayDrives[weekday]
                    val charge = weekdayCharges[weekday]
                    val span = spans.getValue(weekday)
                    InsightsWeekday(
                        weekday = weekday,
                        driveCount = drive?.driveCount ?: 0,
                        distanceKm = drive?.distanceKm ?: BigDecimal.ZERO,
                        drivingMin = drive?.drivingMin,
                        occurrences = span.occurrences,
                        idleMin = (span.elapsedMin - (drive?.drivingMin ?: 0) - (charge?.chargingMin ?: 0)).coerceAtLeast(0),
                    )
                },
            chargeTimes =
                insightsRepository.chargeTimes(window.startUtc, window.endUtc).map {
                    DriveTime(weekday = it.weekday, hour = it.hour, count = it.count)
                },
            speedBuckets =
                TeslaBuckets.SPEED.map { (bucket, bounds) ->
                    SpeedBucket(
                        fromKmh = bounds.first,
                        toKmh = bounds.second,
                        driveCount = speeds[bucket]?.driveCount ?: 0,
                    )
                },
            speedEnergyBuckets =
                TeslaBuckets.SPEED_ENERGY.map { (bucket, bounds) ->
                    val row = speedEnergies[bucket]
                    SpeedEnergyBucket(
                        fromKmh = bounds.first,
                        toKmh = bounds.second,
                        distanceKm = row?.distanceKm ?: BigDecimal.ZERO,
                        ratedRangeUsedKm = row?.ratedRangeUsedKm ?: BigDecimal.ZERO,
                    )
                },
            chargeStartLevels =
                TeslaBuckets.CHARGE_LEVEL.map { (bucket, bounds) ->
                    ChargeLevelBucket(bounds.first, bounds.second, chargeLevels[bucket]?.startCount ?: 0)
                },
            chargeEndLevels =
                TeslaBuckets.CHARGE_LEVEL.map { (bucket, bounds) ->
                    ChargeLevelBucket(bounds.first, bounds.second, chargeLevels[bucket]?.endCount ?: 0)
                },
            chargers =
                insightsRepository.chargers(window.startUtc, window.endUtc).map {
                    Charger(
                        name = it.name,
                        chargeCount = it.chargeCount,
                        energyAddedKwh = it.energyAddedKwh,
                        cost = it.cost,
                        costMissingCount = it.costMissingCount,
                    )
                },
            regions =
                insightsRepository.regions(window.startUtc, window.endUtc).let {
                    Regions(cities = it.cities, states = it.states, countries = it.countries)
                },
            records =
                InsightsRecords(
                    // 행이 왔어도 그 갈래가 쓰는 필드가 null이면(다른 갈래 조건만 만족) 그 기록만 뺀다.
                    longestDistance =
                        records[RECORD_DISTANCE]?.let { r ->
                            r.distanceKm?.let { DistanceRecord(r.driveId, TeslaTime.toKst(r.startedAtUtc), it) }
                        },
                    longestDuration =
                        records[RECORD_DURATION]?.let { r ->
                            r.durationMin?.let { DurationRecord(r.driveId, TeslaTime.toKst(r.startedAtUtc), it) }
                        },
                    bestEfficiency =
                        records[RECORD_EFFICIENCY]?.let { r ->
                            if (r.distanceKm != null && r.ratedRangeUsedKm != null) {
                                EfficiencyRecord(r.driveId, TeslaTime.toKst(r.startedAtUtc), r.distanceKm, r.ratedRangeUsedKm)
                            } else {
                                null
                            }
                        },
                ),
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

    /**
     * 쿼리 셋이 전부다. 서비스가 하는 일은 **범위 계산과 KST 되돌리기뿐**이다 —
     * 솎기와 범위 자르기는 SQL이 한다.
     *
     * **`parkDrain`만 범위를 따르지 않는다** — 최근 7일 고정이다(이유는 `PARK_DRAIN_DAYS` 참고).
     */
    fun batteryWindow(hours: Int): TeslaBatteryWindowResponse {
        if (hours !in MIN_HOURS..MAX_HOURS) {
            throw CustomException(ErrorCode.INVALID_REQUEST, "hours는 $MIN_HOURS~$MAX_HOURS 사이여야 합니다")
        }

        val (fromKst, toKst) = TeslaTime.timelineWindowKst(hours)
        val windowStart = TeslaTime.toUtc(fromKst)
        val windowEnd = TeslaTime.toUtc(toKst)
        val parkDrain = insightsRepository.parkDrainSince(TeslaTime.toUtc(toKst.minusDays(PARK_DRAIN_DAYS)))

        return TeslaBatteryWindowResponse(
            hours = hours,
            from = fromKst,
            to = toKst,
            samples =
                insightsRepository.batterySamples(windowStart, windowEnd).map {
                    BatterySample(
                        at = TeslaTime.toKst(it.dateUtc),
                        batteryLevel = it.batteryLevel,
                        usableBatteryLevel = it.usableBatteryLevel,
                    )
                },
            charges =
                vehicleRepository.chargeSegments(windowStart, windowEnd).map {
                    TimeSegment(from = TeslaTime.toKst(it.fromUtc), to = TeslaTime.toKst(it.toUtc))
                },
            parkDrain = ParkDrain(ratedKm = parkDrain.ratedKm, hours = parkDrain.hours, samples = parkDrain.samples),
        )
    }

    companion object {
        /** `months`의 범위. **0은 전체 기간**이고 상한 60은 실측 기록 길이(60개월)에서 왔다. */
        const val ALL_MONTHS = 0
        const val MAX_MONTHS = 60

        /** `DriveRecordRow.kind`의 값. **`DRIVE_RECORDS_SQL`의 문자열과 같아야 한다.** */
        private const val RECORD_DISTANCE = "distance"
        private const val RECORD_DURATION = "duration"
        private const val RECORD_EFFICIENCY = "efficiency"

        /** `/tesla/battery-window`의 범위. 기본 48시간, 1~168(=7일) — `/tesla/state-timeline`과 같다. */
        const val MIN_HOURS = 1
        const val MAX_HOURS = 168

        /**
         * 팬텀 드레인이 보는 고정 기간. **범위(`hours`)와 무관하다** — 48시간 안에 순수 주차
         * 구간이 하나도 없는 날이 흔해서, 고정해야 숫자가 늘 나온다(실측 최근 7일 19구간).
         */
        private const val PARK_DRAIN_DAYS = 7L
    }
}
