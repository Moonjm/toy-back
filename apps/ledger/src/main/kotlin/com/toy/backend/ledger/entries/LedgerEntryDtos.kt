package com.toy.backend.ledger.entries

import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.LocalDateTime

/** ISO 4217 3자리 대문자. 저장 값이 갈리면 취소 매칭·통화별 집계가 쪼개진다. */
const val CURRENCY_PATTERN = "^[A-Z]{3}$"

data class LedgerEntryRequest(
    val entryAt: LocalDateTime,
    val amount: BigDecimal,
    @field:Pattern(regexp = CURRENCY_PATTERN, message = "통화는 ISO 4217 3자리 대문자여야 합니다")
    val currency: String = "KRW",
    val type: EntryType = EntryType.EXPENSE,
    @field:Size(max = 100)
    val merchant: String? = null,
    @field:Size(max = 500)
    val description: String? = null,
    val categoryId: Long? = null,
)

data class LedgerEntryResponse(
    val id: Long,
    val entryAt: LocalDateTime,
    val amount: BigDecimal,
    val currency: String,
    val type: EntryType,
    val merchant: String?,
    val description: String?,
    val categoryId: Long?,
    val categoryName: String?,
    val source: EntrySource,
)

fun LedgerEntry.toResponse(): LedgerEntryResponse =
    LedgerEntryResponse(
        id = requiredId,
        entryAt = entryAt,
        amount = amount,
        currency = currency,
        type = type,
        merchant = merchant,
        description = description,
        categoryId = category?.requiredId,
        categoryName = category?.name,
        source = source,
    )
