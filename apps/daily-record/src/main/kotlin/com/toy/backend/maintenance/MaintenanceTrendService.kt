package com.toy.backend.maintenance

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.YearMonth

/**
 * 항목·사용량의 월별 추이. **기간이 아니라 「최근 N개월」만 받는다** — 임의의 `from`·`to`를
 * 열면 범위를 무한정 넓힐 수 있다. 비교는 서버가 계산하지 않고 화면이 고른다.
 */
@Service
@Transactional(readOnly = true)
class MaintenanceTrendService(
    private val repository: MaintenanceBillRepository,
) {
    fun trend(
        months: Int,
        today: LocalDate = LocalDate.now(),
    ): TrendResponse {
        val span = months.coerceIn(1, MAX_MONTHS)
        // **이번 달이 상한이다.** 잘못 들어간 미래 달을 여기서 잘라야 「최근 N개월」이 지켜진다.
        val end = YearMonth.from(today)
        val start = end.minusMonths(span - 1L)
        return TrendResponse(
            repository
                .findByYearMonthBetweenOrderByYearMonth(start.toString(), end.toString())
                .map { bill ->
                    TrendMonth(
                        yearMonth = bill.yearMonth,
                        chargedAmount = bill.chargedAmount,
                        items = bill.items.map { BillItemResponse(it.name, it.amount) },
                        usage =
                            BillUsage(
                                electricityKwh = bill.electricityKwh,
                                waterM3 = bill.waterM3,
                                hotWaterM3 = bill.hotWaterM3,
                                heatingGcal = bill.heatingGcal,
                                foodKg = bill.foodKg,
                            ),
                    )
                },
        )
    }

    companion object {
        /**
         * **13이다. 12가 아니다.** 12로 두면 전년 동월이 범위에서 빠져 비교 자체가 성립하지 않는다.
         */
        const val DEFAULT_MONTHS = 13
        const val MAX_MONTHS = 60
    }
}
