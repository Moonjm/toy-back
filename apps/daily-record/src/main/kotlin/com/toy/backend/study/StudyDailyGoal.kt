package com.toy.backend.study

import com.toy.backend.common.entity.BaseEntity
import com.toy.backend.user.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDate

@Entity
@Table(
    name = "study_daily_goals",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_study_daily_goals_user_date", columnNames = ["user_id", "date"]),
    ],
)
class StudyDailyGoal(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,
    @Column(nullable = false)
    var date: LocalDate,
    @Column(nullable = false)
    var goalMinutes: Int,
) : BaseEntity() {
    fun updateGoal(goalMinutes: Int) {
        this.goalMinutes = goalMinutes
    }
}
