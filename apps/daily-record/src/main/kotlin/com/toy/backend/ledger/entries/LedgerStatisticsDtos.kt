package com.toy.backend.ledger.entries

import java.math.BigDecimal
import java.time.LocalDateTime

data class LedgerStatisticsResponse(
    /** 조회 기준 연월 ("2026-07") */
    val yearMonth: String,
    /** 기준월 포함 최근 6개월 원화 지출 추이 (과거 → 기준월 순) */
    val monthlyTrend: List<MonthlyTotal>,
    /** 기준월 출처별 원화 지출 (금액 내림차순) */
    val sourceBreakdown: List<SourceTotal>,
    /** 기준월 통화별 외화 지출 (통화 오름차순, 환산 없음) */
    val foreignTotals: List<CurrencyTotal>,
    /** 기준월 가맹점 TOP 5 (원화, 금액 내림차순, 반복 지출 제외) */
    val topMerchants: List<MerchantTotal>,
    /** 기준월 원화 최대 단건 지출 */
    val maxEntry: MaxEntry?,
    /** 기준월 일평균 원화 지출 — 지출이 있었던 날 기준, 원 단위 반올림(HALF_UP) */
    val dailyAverage: BigDecimal,
)

data class MonthlyTotal(
    val yearMonth: String,
    val krwTotal: BigDecimal,
)

data class SourceTotal(
    val source: EntrySource,
    val krwTotal: BigDecimal,
)

data class CurrencyTotal(
    val currency: String,
    val total: BigDecimal,
)

data class MerchantTotal(
    val merchant: String,
    val krwTotal: BigDecimal,
    val count: Int,
)

data class MaxEntry(
    val merchant: String?,
    val amount: BigDecimal,
    val entryAt: LocalDateTime,
)
