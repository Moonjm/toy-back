package com.toy.backend.ledger

import com.toy.backend.common.entity.withId
import com.toy.backend.ledger.categories.Category
import com.toy.backend.ledger.entries.EntrySource
import com.toy.backend.ledger.entries.EntryType
import com.toy.backend.ledger.entries.LedgerEntry
import com.toy.backend.user.User
import com.toy.backend.user.entity.dummyUser
import java.math.BigDecimal
import java.time.LocalDateTime

fun dummyLedgerEntry(
    user: User = dummyUser(),
    entryAt: LocalDateTime = LocalDateTime.of(2026, 7, 14, 7, 38),
    amount: BigDecimal = BigDecimal("18920"),
    currency: String = "KRW",
    type: EntryType = EntryType.EXPENSE,
    merchant: String? = "제주특별자치도개발",
    description: String? = null,
    category: Category? = null,
    source: EntrySource = EntrySource.SMS,
    id: Long = 1L,
): LedgerEntry =
    LedgerEntry(
        user = user,
        entryAt = entryAt,
        amount = amount,
        currency = currency,
        type = type,
        merchant = merchant,
        description = description,
        category = category,
        source = source,
    ).withId(id)

fun dummyCategory(
    user: User = dummyUser(),
    name: String = "식비",
    id: Long = 1L,
): Category = Category(user = user, name = name).withId(id)
