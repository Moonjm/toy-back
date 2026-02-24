package com.example.backend.dailyrecords

import com.example.backend.categories.Category
import com.example.backend.common.entity.BaseEntity
import com.example.backend.user.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.LocalDate

@Entity
@Table(
    name = "daily_records",
    indexes = [
        Index(name = "idx_daily_records_date", columnList = "date"),
        Index(name = "idx_daily_records_category", columnList = "category_id"),
        Index(name = "idx_daily_records_user", columnList = "user_id"),
    ],
)
class DailyRecord(
    @Column(nullable = false)
    var date: LocalDate,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    var category: Category,
    @Column(nullable = true, length = 20)
    var memo: String? = null,
    @Column(nullable = false)
    var together: Boolean = false,
) : BaseEntity() {
    fun updateDetails(
        date: LocalDate,
        category: Category,
        memo: String?,
        together: Boolean,
    ) {
        this.date = date
        this.category = category
        this.memo = memo
        this.together = together
    }
}
