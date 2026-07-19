package com.toy.backend.ledger.entries

import com.toy.backend.user.User
import org.springframework.data.jpa.repository.JpaRepository
import java.math.BigDecimal
import java.time.LocalDateTime

interface LedgerEntryRepository : JpaRepository<LedgerEntry, Long> {
    fun findAllByUserAndEntryAtGreaterThanEqualAndEntryAtLessThanOrderByEntryAtDesc(
        user: User,
        from: LocalDateTime,
        toExclusive: LocalDateTime,
    ): List<LedgerEntry>

    fun findFirstByUserAndAmountAndCurrencyAndMerchantAndSourceAndEntryAtAfterOrderByEntryAtDesc(
        user: User,
        amount: BigDecimal,
        currency: String,
        merchant: String,
        source: EntrySource,
        after: LocalDateTime,
    ): LedgerEntry?
}
