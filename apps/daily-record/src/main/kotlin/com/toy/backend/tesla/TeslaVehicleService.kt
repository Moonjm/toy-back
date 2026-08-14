package com.toy.backend.tesla

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import org.springframework.stereotype.Service
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
     * 직전 달이 12개월 창 안에 들기 때문이다. 주행 한 번, 충전 한 번, 목록 한 번이 전부다.
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
     * `states` 테이블에는 `online`·`offline`·`asleep` 셋뿐이다
     * (`CREATE TYPE states_status AS ENUM (...)`). `charging`·`driving`은 열린 행에서 파생시킨다 —
     * 테이블 값만 내면 **충전 중에도 online으로만 나온다.**
     *
     * 충전을 먼저 보는 이유: TeslaMate가 죽었다 살아나면 끝나지 않은 주행 행이 남을 수 있고,
     * 그 상태로 충전을 시작하면 두 조건이 동시에 참이 된다. 그때 사실에 가까운 쪽은 충전이다.
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

    companion object {
        /** 기준 달을 포함해 거슬러 세는 개월 수. */
        const val TREND_MONTHS = 12
        private const val EARTH_RADIUS_M = 6_371_000.0
    }
}
