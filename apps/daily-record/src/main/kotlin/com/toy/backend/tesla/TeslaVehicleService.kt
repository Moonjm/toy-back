package com.toy.backend.tesla

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import org.springframework.stereotype.Service
import java.time.YearMonth

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
        val windowEnd = TeslaTime.monthRangeUtc(yearMonth).second

        val drives = vehicleRepository.driveMonthly(windowStart, windowEnd).associateBy { it.month }
        val charges = chargeRepository.chargeMonthly(windowStart, windowEnd).associateBy { it.month }
        val trend = (0 until TREND_MONTHS).map { statOf(oldest.plusMonths(it.toLong()), drives, charges) }

        val (monthStart, monthEnd) = TeslaTime.monthRangeUtc(yearMonth)
        return TeslaSummaryResponse(
            month = statOf(yearMonth, drives, charges),
            previous = statOf(yearMonth.minusMonths(1), drives, charges),
            trend = trend,
            charges = chargeRepository.findMonthCharges(monthStart, monthEnd).map { it.toItem() },
        )
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
    }
}
