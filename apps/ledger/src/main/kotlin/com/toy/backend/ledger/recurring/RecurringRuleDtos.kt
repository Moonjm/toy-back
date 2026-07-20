package com.toy.backend.ledger.recurring

import com.toy.backend.ledger.entries.EntryType
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.math.BigDecimal

data class RecurringRuleCreateRequest(
    val entryId: Long,
    @field:Min(1)
    @field:Max(31)
    val dayOfMonth: Int? = null,
)

data class RecurringRuleUpdateRequest(
    @field:Min(1)
    @field:Max(31)
    val dayOfMonth: Int,
    val amount: BigDecimal,
    @field:NotBlank
    @field:Size(max = 3)
    val currency: String,
    val type: EntryType,
    @field:Size(max = 100)
    val merchant: String? = null,
    @field:Size(max = 500)
    val description: String? = null,
    val categoryId: Long? = null,
    val active: Boolean,
)

data class RecurringRuleResponse(
    val id: Long,
    val dayOfMonth: Int,
    val amount: BigDecimal,
    val currency: String,
    val type: EntryType,
    val merchant: String?,
    val description: String?,
    val categoryId: Long?,
    val categoryName: String?,
    val active: Boolean,
)

fun RecurringRule.toResponse(): RecurringRuleResponse =
    RecurringRuleResponse(
        id = requiredId,
        dayOfMonth = dayOfMonth,
        amount = amount,
        currency = currency,
        type = type,
        merchant = merchant,
        description = description,
        categoryId = category?.requiredId,
        categoryName = category?.name,
        active = active,
    )
