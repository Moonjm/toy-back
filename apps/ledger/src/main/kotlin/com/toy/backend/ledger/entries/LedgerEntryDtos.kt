package com.toy.backend.ledger.entries

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.LocalDateTime

data class LedgerEntryRequest(
    val entryAt: LocalDateTime,
    val amount: BigDecimal,
    @field:NotBlank
    @field:Size(max = 3)
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
