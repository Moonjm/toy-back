package com.toy.backend.diet.feedback

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
import java.time.LocalDateTime

/**
 * 하루 마감 피드백 캐시. **집계값을 저장하는 표가 아니다** — 하루 합계와 점수는 `Meal` 합산으로
 * 언제든 다시 구할 수 있고, 여기 있는 이유는 LLM 호출 결과를 재사용하기 위해서다.
 */
@Entity
@Table(
    name = "daily_diet_feedbacks",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_daily_diet_feedbacks_user_date", columnNames = ["user_id", "date"]),
    ],
)
class DailyDietFeedback(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,
    @Column(nullable = false)
    var date: LocalDate,
    @Column(name = "day_score", nullable = false)
    var dayScore: Int,
    @Column(columnDefinition = "text")
    var feedback: String?,
    @Column(name = "generated_at", nullable = false)
    var generatedAt: LocalDateTime,
) : BaseEntity() {
    fun update(
        dayScore: Int,
        feedback: String?,
        generatedAt: LocalDateTime,
    ) {
        this.dayScore = dayScore
        this.feedback = feedback
        this.generatedAt = generatedAt
    }
}
