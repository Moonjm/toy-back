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
    name = "daily_diet_feedback",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_daily_diet_feedback_user_date", columnNames = ["user_id", "date"]),
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
    /** 마커를 찍는다 — `generatedAt`이 「이 시점의 끼니 구성을 기준으로 생성을 시작했다」는 뜻이 된다. */
    fun update(
        dayScore: Int,
        feedback: String?,
        generatedAt: LocalDateTime,
    ) {
        this.dayScore = dayScore
        this.feedback = feedback
        this.generatedAt = generatedAt
    }

    /**
     * 생성된 문장을 싣는다. **`generatedAt`은 건드리지 않는다** — 마커를 찍은 시각으로 남겨야
     * 생성 중에 끼니가 바뀐 경우 무효화 판정(`generatedAt < 최종 Meal.updatedAt`)에 걸린다.
     * 여기서 끝난 시각을 다시 찍으면 그 수정보다 나중이 되어, **낡은 문장이 유효한 것으로 굳는다.**
     */
    fun publish(
        dayScore: Int,
        feedback: String,
    ) {
        this.dayScore = dayScore
        this.feedback = feedback
    }
}
