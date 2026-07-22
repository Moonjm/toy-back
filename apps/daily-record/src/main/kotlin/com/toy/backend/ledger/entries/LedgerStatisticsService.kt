package com.toy.backend.ledger.entries

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import com.toy.backend.user.User
import com.toy.backend.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth

/**
 * 가계부 통계 — 기준월 포함 최근 6개월을 한 번에 조회해 메모리에서 집계한다.
 * (2인 사용·월 수백 건 규모라 DB 집계 쿼리 대신 단순한 방식을 택한다)
 */
@Service
@Transactional(readOnly = true)
class LedgerStatisticsService(
    private val repository: LedgerEntryRepository,
    private val userRepository: UserRepository,
) {
    fun statistics(
        username: String,
        yearMonth: YearMonth,
    ): LedgerStatisticsResponse {
        val user = findUser(username)
        val startMonth = yearMonth.minusMonths(TREND_MONTHS - 1L)
        val expenses =
            repository
                .search(
                    user = user,
                    start = startMonth.atDay(1).atStartOfDay(),
                    end = yearMonth.plusMonths(1).atDay(1).atStartOfDay(),
                    keyword = null,
                ).filter { it.type == EntryType.EXPENSE }

        val monthlyTrend =
            (0 until TREND_MONTHS).map { offset ->
                val month = startMonth.plusMonths(offset.toLong())
                val total =
                    expenses
                        .filter { isKrw(it) && YearMonth.from(it.entryAt) == month }
                        .sumOf { it.amount }
                MonthlyTotal(yearMonth = month.toString(), krwTotal = total)
            }

        val current = expenses.filter { YearMonth.from(it.entryAt) == yearMonth }
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
                .filter { !it.merchant.isNullOrBlank() }
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
            yearMonth = yearMonth.toString(),
            monthlyTrend = monthlyTrend,
            sourceBreakdown = sourceBreakdown,
            foreignTotals = foreignTotals,
            topMerchants = topMerchants,
            maxEntry = maxEntry,
            dailyAverage = dailyAverage,
        )
    }

    private fun isKrw(entry: LedgerEntry): Boolean = entry.currency.uppercase() == "KRW"

    private fun findUser(username: String): User =
        userRepository.findByUsername(username)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)

    companion object {
        private const val TREND_MONTHS = 6
        private const val TOP_MERCHANT_COUNT = 5
    }
}
