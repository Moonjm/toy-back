package com.toy.backend.ledger.entries

import com.toy.backend.user.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal
import java.time.LocalDateTime

interface LedgerEntryRepository : JpaRepository<LedgerEntry, Long> {
    fun findAllByUserAndEntryAtGreaterThanEqualAndEntryAtLessThanOrderByEntryAtDesc(
        user: User,
        from: LocalDateTime,
        toExclusive: LocalDateTime,
    ): List<LedgerEntry>

    /** 취소 매칭 대상: 같은 사용자·금액·통화·가맹점·source의 기간 내 최신 건. */
    @Query(
        """
        select e from LedgerEntry e
        where e.user = :user
          and e.amount = :amount
          and e.currency = :currency
          and e.merchant = :merchant
          and e.source = :source
          and e.entryAt > :after
        order by e.entryAt desc
        limit 1
        """,
    )
    fun findLatestCancellable(
        @Param("user") user: User,
        @Param("amount") amount: BigDecimal,
        @Param("currency") currency: String,
        @Param("merchant") merchant: String,
        @Param("source") source: EntrySource,
        @Param("after") after: LocalDateTime,
    ): LedgerEntry?
}
