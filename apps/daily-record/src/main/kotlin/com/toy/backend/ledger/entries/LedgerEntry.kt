package com.toy.backend.ledger.entries

import com.toy.backend.common.entity.BaseEntity
import com.toy.backend.user.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDateTime

enum class EntryType { EXPENSE, INCOME }

enum class EntrySource { MANUAL, SMS, KAKAO_PAY, RECURRING, IMPORT }

@Entity
@Table(
    name = "ledger_entries",
    indexes = [
        Index(name = "idx_ledger_entries_user_entry_at", columnList = "user_id, entry_at"),
    ],
)
class LedgerEntry(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,
    @Column(name = "entry_at", nullable = false)
    var entryAt: LocalDateTime,
    @Column(nullable = false, precision = 19, scale = 4)
    var amount: BigDecimal,
    @Column(nullable = false, length = 3)
    var currency: String = "KRW",
    // columnDefinition 명시로 enum CHECK 제약 생성을 막는다 (ddl-auto:update가 제약을 갱신하지 못함)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(10)")
    var type: EntryType = EntryType.EXPENSE,
    @Column(length = 100)
    var merchant: String? = null,
    @Column(length = 500)
    var description: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20)")
    var source: EntrySource = EntrySource.MANUAL,
) : BaseEntity() {
    fun updateDetails(
        entryAt: LocalDateTime,
        amount: BigDecimal,
        currency: String,
        type: EntryType,
        merchant: String?,
        description: String?,
    ) {
        this.entryAt = entryAt
        this.amount = amount
        this.currency = currency
        this.type = type
        this.merchant = merchant
        this.description = description
    }
}
