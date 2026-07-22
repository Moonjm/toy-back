package com.toy.backend.ledger.recurring

import com.toy.backend.common.entity.BaseEntity
import com.toy.backend.ledger.entries.EntryType
import com.toy.backend.user.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.math.BigDecimal

/** 월 반복 지출/수입 규칙. entry 값의 복사본이라 원본 entry와 독립적이다. */
@Entity
@Table(name = "ledger_recurring_rules")
class RecurringRule(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,
    @Column(name = "day_of_month", nullable = false)
    var dayOfMonth: Int,
    @Column(nullable = false, precision = 19, scale = 4)
    var amount: BigDecimal,
    @Column(nullable = false, length = 3)
    var currency: String,
    // columnDefinition 명시로 enum CHECK 제약 생성을 막는다 (ddl-auto:update가 제약을 갱신하지 못함)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(10)")
    var type: EntryType,
    @Column(length = 100)
    var merchant: String? = null,
    @Column(length = 500)
    var description: String? = null,
    @Column(nullable = false)
    var active: Boolean = true,
    @Column(name = "last_generated_month", length = 7)
    var lastGeneratedMonth: String? = null,
) : BaseEntity() {
    fun updateDetails(
        dayOfMonth: Int,
        amount: BigDecimal,
        currency: String,
        type: EntryType,
        merchant: String?,
        description: String?,
        active: Boolean,
    ) {
        this.dayOfMonth = dayOfMonth
        this.amount = amount
        this.currency = currency
        this.type = type
        this.merchant = merchant
        this.description = description
        this.active = active
    }
}
