package com.toy.backend.ledger.categories

import com.toy.backend.common.entity.BaseEntity
import com.toy.backend.user.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

/** 사용자별 지출 분류. 이름은 사용자 내에서 유일하다. */
@Entity
@Table(
    name = "ledger_categories",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_ledger_categories_user_name", columnNames = ["user_id", "name"]),
    ],
)
class Category(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,
    @Column(nullable = false, length = 50)
    var name: String,
) : BaseEntity() {
    fun rename(name: String) {
        this.name = name
    }
}
