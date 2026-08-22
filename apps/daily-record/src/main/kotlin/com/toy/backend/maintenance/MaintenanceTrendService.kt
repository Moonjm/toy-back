package com.toy.backend.maintenance

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.YearMonth

/**
 * 항목·사용량의 월별 추이.
 *
 * **기간을 자유롭게 받지 않고 「최근 N개월」만 받는다.** 임의의 `from`·`to`를 열면 요청
 * 한 번으로 범위를 무한정 넓힐 수 있다(`dispatch`가 같은 이유로 연월 하나만 받는다).
 *
 * **전년 동월 비교를 서버가 계산하지 않는다.** 13개월을 통째로 내려 주면 화면이 어떤 달과
 * 어떤 달을 견줄지 고를 수 있고, 그래프마다 따로 부르지 않아도 된다. 비교 방식이 바뀔 때마다
 * API를 고칠 이유도 사라진다.
 *
 * 2인 사용에 월 한 건이라 DB 집계 대신 그대로 읽어 옮긴다(`LedgerStatisticsService`와 같은 판단).
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
        val start = YearMonth.from(today).minusMonths(span - 1L)
        return TrendResponse(
            repository
                .findByYearMonthGreaterThanEqualOrderByYearMonth(start.toString())
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
