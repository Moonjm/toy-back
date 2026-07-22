package com.toy.backend.ledger.entries

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import com.toy.backend.user.User
import com.toy.backend.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * 가계부 통계 — 필요한 기간을 한 번에 조회해 메모리에서 집계한다.
 * (2인 사용·월 수백 건 규모라 DB 집계 쿼리 대신 단순한 방식을 택한다)
 */
@Service
@Transactional(readOnly = true)
class LedgerStatisticsService(
    private val repository: LedgerEntryRepository,
    private val userRepository: UserRepository,
) {
    /** 월별 통계 — 기준월 포함 최근 6개월 추이 + 기준월 집계. 직전 기간은 지난달. */
    fun statistics(
        username: String,
        yearMonth: YearMonth,
    ): LedgerStatisticsResponse {
        val user = findUser(username)
        val startMonth = yearMonth.minusMonths(TREND_MONTHS - 1L)
        val expenses =
            fetchExpenses(
                user = user,
                start = startMonth.atDay(1).atStartOfDay(),
                end = yearMonth.plusMonths(1).atDay(1).atStartOfDay(),
            )

        val monthlyTrend =
            (0 until TREND_MONTHS).map { offset ->
                val month = startMonth.plusMonths(offset.toLong())
                MonthlyTotal(
                    yearMonth = month.toString(),
                    krwTotal = krwTotal(expenses) { YearMonth.from(it.entryAt) == month },
                )
            }

        val previousMonth = yearMonth.minusMonths(1)
        return aggregate(
            periodLabel = yearMonth.toString(),
            monthlyTrend = monthlyTrend,
            current = expenses.filter { YearMonth.from(it.entryAt) == yearMonth },
            previousTotal = krwTotal(expenses) { YearMonth.from(it.entryAt) == previousMonth },
        )
    }

    /** 연별 통계 — 기준연 12개월 추이 + 기준연 집계. 직전 기간은 지난해. */
    fun yearlyStatistics(
        username: String,
        year: Int,
    ): LedgerStatisticsResponse {
        val user = findUser(username)
        // 지난해 합계(previousTotal)까지 한 번에 계산하려고 전년 1월부터 조회한다.
        val expenses =
            fetchExpenses(
                user = user,
                start = LocalDate.of(year - 1, 1, 1).atStartOfDay(),
                end = LocalDate.of(year + 1, 1, 1).atStartOfDay(),
            )

        val monthlyTrend =
            (1..MONTHS_IN_YEAR).map { monthValue ->
                val month = YearMonth.of(year, monthValue)
                MonthlyTotal(
                    yearMonth = month.toString(),
                    krwTotal = krwTotal(expenses) { YearMonth.from(it.entryAt) == month },
                )
            }

        return aggregate(
            periodLabel = year.toString(),
            monthlyTrend = monthlyTrend,
            current = expenses.filter { it.entryAt.year == year },
            previousTotal = krwTotal(expenses) { it.entryAt.year == year - 1 },
        )
    }

    /** 기간 지출 목록에서 공통 집계(출처·외화·가맹점 TOP·최대 단건·일평균)를 만든다. */
    private fun aggregate(
        periodLabel: String,
        monthlyTrend: List<MonthlyTotal>,
        current: List<LedgerEntry>,
        previousTotal: BigDecimal,
    ): LedgerStatisticsResponse {
        val currentKrw = current.filter { isKrw(it) }

        val sourceBreakdown =
            currentKrw
                .groupBy { it.source }
                .map { (source, list) -> SourceTotal(source = source, krwTotal = list.sumOf { it.amount }) }
                .sortedByDescending { it.krwTotal }

        val foreignTotals =
            current
                .filterNot { isKrw(it) }
                .groupBy { it.currency.uppercase() }
                .map { (currency, list) -> CurrencyTotal(currency = currency, total = list.sumOf { it.amount }) }
                .sortedBy { it.currency }

        val topMerchants =
            currentKrw
                // 반복(고정비)은 "어디에 많이 썼나"를 보는 순위라 제외한다.
                .filter { it.source != EntrySource.RECURRING && !it.merchant.isNullOrBlank() }
                .groupBy { it.merchant!! }
                .map { (merchant, list) ->
                    MerchantTotal(merchant = merchant, krwTotal = list.sumOf { it.amount }, count = list.size)
                }.sortedByDescending { it.krwTotal }
                .take(TOP_MERCHANT_COUNT)

        val maxEntry =
            currentKrw.maxByOrNull { it.amount }?.let {
                MaxEntry(merchant = it.merchant, amount = it.amount, entryAt = it.entryAt)
            }

        val expenseDays = currentKrw.map { it.entryAt.toLocalDate() }.distinct().size
        val dailyAverage =
            if (expenseDays == 0) {
                BigDecimal.ZERO
            } else {
                currentKrw.sumOf { it.amount }.divide(BigDecimal(expenseDays), 0, RoundingMode.HALF_UP)
            }

        return LedgerStatisticsResponse(
            yearMonth = periodLabel,
            monthlyTrend = monthlyTrend,
            previousTotal = previousTotal,
            sourceBreakdown = sourceBreakdown,
            foreignTotals = foreignTotals,
            topMerchants = topMerchants,
            maxEntry = maxEntry,
            dailyAverage = dailyAverage,
        )
    }

    private fun fetchExpenses(
        user: User,
        start: LocalDateTime,
        end: LocalDateTime,
    ): List<LedgerEntry> =
        repository
            .search(user = user, start = start, end = end, keyword = null)
            .filter { it.type == EntryType.EXPENSE }

    private fun krwTotal(
        expenses: List<LedgerEntry>,
        predicate: (LedgerEntry) -> Boolean,
    ): BigDecimal = expenses.filter { isKrw(it) && predicate(it) }.sumOf { it.amount }

    private fun isKrw(entry: LedgerEntry): Boolean = entry.currency.uppercase() == "KRW"

    private fun findUser(username: String): User =
        userRepository.findByUsername(username)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)

    companion object {
        private const val TREND_MONTHS = 6
        private const val MONTHS_IN_YEAR = 12
        private const val TOP_MERCHANT_COUNT = 5
    }
}
