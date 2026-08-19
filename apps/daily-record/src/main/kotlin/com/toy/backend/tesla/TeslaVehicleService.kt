package com.toy.backend.tesla

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.YearMonth
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * **`@Transactional`을 붙이지 않는다.** 기본 트랜잭션 매니저는 daily-record 커넥션의 것이라
 * TeslaMate 쪽 SQL에 아무 효력이 없다. 이 서비스는 읽기만 한다.
 */
@Service
class TeslaVehicleService(
    private val vehicleRepository: TeslaVehicleRepository,
    private val chargeRepository: TeslaChargeRepository,
) {
    /**
     * 12개월 추이·이번 달·직전 달이 **한 벌의 그룹 집계**에서 나온다 —
     * 직전 달이 12개월 범위 안에 들기 때문이다. 주행 한 번, 충전 한 번, 목록 한 번이 전부다.
     */
    fun summary(yearMonth: YearMonth?): TeslaSummaryResponse {
        if (yearMonth == null) {
            throw CustomException(ErrorCode.INVALID_REQUEST, "yearMonth는 필수입니다")
        }
        val oldest = yearMonth.minusMonths((TREND_MONTHS - 1).toLong())
        val windowStart = TeslaTime.monthRangeUtc(oldest).first
        val (monthStart, monthEnd) = TeslaTime.monthRangeUtc(yearMonth)
        val windowEnd = monthEnd

        val drives = vehicleRepository.driveMonthly(windowStart, windowEnd).associateBy { it.month }
        val charges = chargeRepository.chargeMonthly(windowStart, windowEnd).associateBy { it.month }
        val trend = (0 until TREND_MONTHS).map { statOf(oldest.plusMonths(it.toLong()), drives, charges) }

        return TeslaSummaryResponse(
            month = statOf(yearMonth, drives, charges),
            previous = statOf(yearMonth.minusMonths(1), drives, charges),
            trend = trend,
            charges = chargeRepository.findMonthCharges(monthStart, monthEnd).map { it.toItem() },
        )
    }

    fun status(): TeslaStatusResponse {
        val position = vehicleRepository.findLatestPosition()
        val openState = vehicleRepository.findOpenState()
        val activity = vehicleRepository.findActivity()
        return TeslaStatusResponse(
            asOf = position?.dateUtc?.let { TeslaTime.toKst(it) },
            state = resolveState(activity, openState),
            stateSince = openState?.startDateUtc?.let { TeslaTime.toKst(it) },
            batteryLevel = position?.batteryLevel,
            usableBatteryLevel = position?.usableBatteryLevel,
            ratedRangeKm = position?.ratedRangeKm,
            estRangeKm = position?.estRangeKm,
            odometerKm = position?.odometerKm,
            insideTempC = position?.insideTempC,
            outsideTempC = position?.outsideTempC,
            climateOn = position?.climateOn,
            locationName = position?.let { geofenceNameAt(it) },
            tpmsBar = position?.let { TpmsBar(it.tpmsFl, it.tpmsFr, it.tpmsRl, it.tpmsRr) },
        )
    }

    /**
     * 쿼리 한 번이 전부다. **중앙값·월 경계(KST)·표본 조건은 SQL이 하고**, 여기서는 행을
     * 표본으로 옮기고 오래된 것부터로 못 박기만 한다.
     *
     * 표본이 없는 달의 자리를 채우지 않는다 — `summary`의 `trend`와 반대다. 그쪽은
     * 「그 달에 안 탔다」와 「기록이 없다」를 구분해야 하지만, 열화는 월 경계가 의미를 갖는
     * 값이 아니다. 선을 이을지 끊을지는 앱이 정한다.
     */
    fun batteryHealth(): TeslaBatteryHealthResponse =
        TeslaBatteryHealthResponse(
            samples =
                vehicleRepository
                    .batteryHealthMonthly()
                    .sortedBy { it.month }
                    .map {
                        BatteryHealthSample(
                            yearMonth = it.month,
                            fullRangeKm = it.fullRangeKm,
                            capacityKwh = it.capacityKwh,
                            sampleCount = it.sampleCount,
                            capacitySampleCount = it.capacitySampleCount,
                        )
                    },
        )

    /**
     * 쿼리 다섯 번이 전부다. **한 SQL에 몰지 않는다** — 서로 다른 GROUP BY 넷을 UNION으로
     * 붙였다가 다시 갈라 읽어야 한다. `drives`는 5,000행대라 다섯 번 훑어도 싸다.
     *
     * 서비스가 하는 일은 **빈 버킷 자리 채움과 행→DTO 변환뿐**이다. 합계·KST 변환·정렬은
     * SQL이 한다.
     */
    fun driveInsights(months: Int): TeslaDriveInsightsResponse {
        if (months !in MIN_MONTHS..MAX_MONTHS) {
            throw CustomException(ErrorCode.INVALID_REQUEST, "months는 $MIN_MONTHS~$MAX_MONTHS 사이여야 합니다")
        }

        val temperatures = vehicleRepository.driveTemperatureBuckets(months).associateBy { it.bucket }
        val distances = vehicleRepository.driveDistanceBuckets(months).associateBy { it.bucket }
        val stats = vehicleRepository.driveStats()

        return TeslaDriveInsightsResponse(
            months = months,
            efficiencyKwhPerKm = vehicleRepository.carEfficiency(),
            temperatureBuckets =
                TEMPERATURE_BUCKETS.map { (bucket, bounds) ->
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
                vehicleRepository.driveTimes(months).map {
                    DriveTime(weekday = it.weekday, hour = it.hour, count = it.count)
                },
            distanceBuckets =
                DISTANCE_BUCKETS.map { (bucket, bounds) ->
                    val row = distances[bucket]
                    DistanceBucket(
                        fromKm = bounds.first,
                        toKm = bounds.second,
                        driveCount = row?.driveCount ?: 0,
                        distanceKm = row?.distanceKm ?: BigDecimal.ZERO,
                    )
                },
            places =
                vehicleRepository.drivePlaces(months).map {
                    DrivePlace(name = it.name, driveCount = it.driveCount, distanceKm = it.distanceKm)
                },
            maxSpeedKmh = stats.maxSpeedKmh,
            totalDistanceKm = stats.totalDistanceKm,
            recordedMonths = stats.recordedMonths,
        )
    }

    /**
     * 쿼리 셋이 전부다. **서버는 세 계열을 그대로 내고 겹침 해소는 화면이 한다** —
     * 하나의 띠로 합치려면 구간 산술이 필요한데 그 로직은 SQL로도 코틀린으로도 만만치 않다.
     *
     * 서비스가 하는 일은 **범위 계산과 KST 되돌리기뿐**이다. 범위 자르기(`GREATEST`/`LEAST`)와
     * 유령 거르기(24시간 범위)는 SQL이 한다.
     */
    fun stateTimeline(hours: Int): TeslaStateTimelineResponse {
        if (hours !in MIN_HOURS..MAX_HOURS) {
            throw CustomException(ErrorCode.INVALID_REQUEST, "hours는 $MIN_HOURS~$MAX_HOURS 사이여야 합니다")
        }

        val (fromKst, toKst) = TeslaTime.timelineWindowKst(hours)
        val windowStart = TeslaTime.toUtc(fromKst)
        val windowEnd = TeslaTime.toUtc(toKst)

        return TeslaStateTimelineResponse(
            hours = hours,
            from = fromKst,
            to = toKst,
            states =
                vehicleRepository.stateSegments(windowStart, windowEnd).map {
                    StateSegment(state = it.state, from = TeslaTime.toKst(it.fromUtc), to = TeslaTime.toKst(it.toUtc))
                },
            drives = vehicleRepository.driveSegments(windowStart, windowEnd).map { it.toSegment() },
            charges = vehicleRepository.chargeSegments(windowStart, windowEnd).map { it.toSegment() },
        )
    }

    /**
     * `states` 테이블에는 `online`·`offline`·`asleep` 셋뿐이다
     * (`CREATE TYPE states_status AS ENUM (...)`). `charging`·`driving`은 열린 행에서 파생시킨다 —
     * 테이블 값만 내면 **충전 중에도 online으로만 나온다.**
     *
     * 충전을 먼저 보는 이유: TeslaMate가 죽었다 살아나면 끝나지 않은 주행 행이 남을 수 있고,
     * 그 상태로 충전을 시작하면 두 조건이 동시에 참이 된다. 그때 사실에 가까운 쪽은 충전이다.
     *
     * **마감되지 않은 세션을 거르는 것은 리포지토리의 24시간 범위가 한다.** 여기서는 이미 걸러진
     * 결과를 받는다 — 그 범위가 없으면 몇 년 전 유령 하나가 `charging`을 영원히 참으로 만든다.
     */
    private fun resolveState(
        activity: ActivityRow,
        openState: StateRow?,
    ): String? =
        when {
            activity.charging -> "charging"
            activity.driving -> "driving"
            else -> openState?.state
        }

    /**
     * 반경 안에 드는 것 중 가장 가까운 하나. 없으면 null이다.
     *
     * **판정을 서버에서 한다.** TeslaMate가 `cube`·`earthdistance`를 깔아 두지만, 그 확장은
     * 상류가 자기 필요로 깐 것이고 지오펜스는 몇 개 수준이다. 전부 읽어 재는 편이 낫다.
     */
    private fun geofenceNameAt(position: PositionRow): String? {
        val lat = position.latitude?.toDouble() ?: return null
        val lon = position.longitude?.toDouble() ?: return null
        return vehicleRepository
            .findGeofences()
            .map { it to distanceMeters(lat, lon, it.latitude.toDouble(), it.longitude.toDouble()) }
            .filter { (fence, distance) -> distance <= fence.radiusM }
            .minByOrNull { (_, distance) -> distance }
            ?.first
            ?.name
    }

    /** 하버사인. 지구를 구로 보고 재며, 수 km 규모에서 오차는 판정에 영향을 주지 않는다. */
    private fun distanceMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a =
            sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun statOf(
        month: YearMonth,
        drives: Map<YearMonth, DriveMonthRow>,
        charges: Map<YearMonth, ChargeMonthRow>,
    ): MonthlyStat {
        val drive = drives[month]
        val charge = charges[month]
        return MonthlyStat(
            yearMonth = month,
            distanceKm = drive?.distanceKm,
            drivingMin = drive?.drivingMin,
            driveCount = drive?.count,
            energyAddedKwh = charge?.energyAddedKwh,
            energyUsedKwh = charge?.energyUsedKwh,
            cost = charge?.cost,
            chargeCount = charge?.count,
        )
    }

    private fun SegmentRow.toSegment() = TimeSegment(from = TeslaTime.toKst(fromUtc), to = TeslaTime.toKst(toUtc))

    companion object {
        /** 기준 달을 포함해 거슬러 세는 개월 수. */
        const val TREND_MONTHS = 12

        /** `/tesla/drive-insights`의 범위. 기본 12개월, 1~60. */
        const val MIN_MONTHS = 1
        const val MAX_MONTHS = 60

        /**
         * `/tesla/state-timeline`의 범위. 기본 24시간, 1~168(=7일).
         *
         * 상한을 168로 둔 이유는 초판의 30일 상한과 같다 — 화면이 지금 쓰는 값(24)보다
         * 넉넉하되, 한 응답에 담기는 구간 수가 손댈 만한 크기를 넘지 않는 선이다.
         * 실측 최근 24시간이 22구간, 최근 7일이 168구간이다.
         */
        const val MIN_HOURS = 1
        const val MAX_HOURS = 168

        /**
         * 온도 버킷의 **응답 라벨**이다(℃). `bucket` 번호 → (`fromC`, `toC`).
         * 하한/상한이 없으면 null이고, 경계는 `from` 포함·`to` 미만이다.
         *
         * **`JdbcTeslaVehicleRepository.DRIVE_TEMPERATURE_BUCKETS_SQL`의 `CASE`와 같은 숫자여야
         * 한다** — 거기는 임계값으로, 여기는 라벨로 쓴다. 한쪽만 고치면 응답의 라벨과 실제
         * 집계가 어긋난다. 다섯 개인 이유는 계절이 갈리는 최소 단위라서고, 앱이 버킷을 정하면
         * 서버가 원자료를 통째로 보내야 한다.
         */
        private val TEMPERATURE_BUCKETS: List<Pair<Int, Pair<Int?, Int?>>> =
            listOf(
                1 to (null to 0),
                2 to (0 to 10),
                3 to (10 to 20),
                4 to (20 to 30),
                5 to (30 to null),
            )

        /**
         * 거리 버킷의 **응답 라벨**이다(km). `bucket` 번호 → (`fromKm`, `toKm`).
         *
         * **`JdbcTeslaVehicleRepository.DRIVE_DISTANCE_BUCKETS_SQL`의 `CASE`와 같은 숫자여야
         * 한다.**
         */
        private val DISTANCE_BUCKETS: List<Pair<Int, Pair<Int, Int?>>> =
            listOf(
                1 to (0 to 5),
                2 to (5 to 20),
                3 to (20 to 50),
                4 to (50 to 100),
                5 to (100 to null),
            )
        private const val EARTH_RADIUS_M = 6_371_000.0
    }
}
