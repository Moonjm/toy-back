package com.toy.backend.diet.meal

import com.toy.backend.common.entity.BaseEntity
import com.toy.backend.diet.AnalysisStatus
import com.toy.backend.diet.profile.NutritionTargets
import com.toy.backend.user.User
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import java.time.LocalDate

enum class MealType {
    BREAKFAST,
    LUNCH,
    DINNER,
    SNACK,
    ;

    /**
     * 같은 날 같은 종류를 다시 확정하면 **기존 끼니에 합칠 것인가**.
     *
     * **간식만 아니다.** 아침·점심·저녁은 하루에 하나가 자연스럽지만 간식은 본래 여러 번이다.
     * 오전 과자와 밤 아이스크림을 한 카드에 합치면 끼니 점수가 뒤섞이고, 사진 없는 기록을
     * 열어 둔 이유(「과자 하나를 적으려고 사진을 찍게 만들지 말자」)와도 어긋난다.
     *
     * 판단을 여기 두는 것은 서비스에 `if (mealType != SNACK)`을 흩어 두지 않기 위해서다.
     */
    val mergesWithinDay: Boolean get() = this != SNACK
}

/**
 * **확정된 끼니만 존재한다.** 인식만 되고 확정되지 않은 결과는 `MealAnalysis`에 있고 `Meal`이 되지
 * 않는다 — 덕분에 하루 집계·점수·음식 빈도 쿼리에 "확정된 것만" 조건을 붙일 필요가 없다.
 *
 * `status`는 **피드백 생성 상태**다(PENDING → COMPLETED/FAILED). 확정 시점에 점수는 동기로
 * 계산되고 피드백만 뒤에서 생성되므로, iOS는 이 값으로 피드백 도착을 폴링한다.
 */
@Entity
@Table(
    name = "meal",
    indexes = [
        Index(name = "idx_meal_user_date", columnList = "user_id, date"),
    ],
)
class Meal(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,
    @Column(nullable = false)
    var date: LocalDate,
    @Enumerated(EnumType.STRING)
    @Column(name = "meal_type", nullable = false, columnDefinition = "varchar(20)")
    var mealType: MealType,
    /** 확정 시점 몸무게 — 이 끼니가 몇 kg 기준으로 채점됐는지 설명할 수 있어야 한다. */
    @Column(name = "weight_kg", nullable = false)
    var weightKg: Double,
    @Column(name = "target_kcal", nullable = false)
    var targetKcal: Int,
    @Column(name = "target_carbs_g", nullable = false)
    var targetCarbsG: Int,
    @Column(name = "target_protein_g", nullable = false)
    var targetProteinG: Int,
    @Column(name = "target_fat_g", nullable = false)
    var targetFatG: Int,
    @Column(name = "target_sugar_g", nullable = false)
    var targetSugarG: Int,
    @Column(name = "target_sodium_mg", nullable = false)
    var targetSodiumMg: Int,
    @Column(name = "target_fiber_g", nullable = false)
    var targetFiberG: Int,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20)")
    var status: AnalysisStatus = AnalysisStatus.PENDING,
    @Column
    var score: Int? = null,
    @Column(name = "total_kcal", nullable = false)
    var totalKcal: Double = 0.0,
    @Column(name = "carbs_g", nullable = false)
    var carbsG: Double = 0.0,
    @Column(name = "protein_g", nullable = false)
    var proteinG: Double = 0.0,
    @Column(name = "fat_g", nullable = false)
    var fatG: Double = 0.0,
    @Column(name = "sugar_g", nullable = false)
    var sugarG: Double = 0.0,
    @Column(name = "sodium_mg", nullable = false)
    var sodiumMg: Double = 0.0,
    @Column(name = "fiber_g", nullable = false)
    var fiberG: Double = 0.0,
    @Column(columnDefinition = "text")
    var feedback: String? = null,
) : BaseEntity() {
    @OneToMany(mappedBy = "meal", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("sortOrder asc")
    var photos: MutableList<MealPhoto> = mutableListOf()

    @OneToMany(mappedBy = "meal", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("id asc")
    var items: MutableList<MealItem> = mutableListOf()

    /** 항목 **수정**은 전체 교체다 — 화면에서 목록째 넘어오므로 남은 것을 추려낼 필요가 없다. */
    fun replaceItems(newItems: List<MealItem>) {
        items.clear()
        items.addAll(newItems)
        recalculateTotals()
    }

    /**
     * 같은 날 같은 끼니에 다시 기록했을 때 **기존 항목 위에 얹는다.**
     *
     * `replaceItems`를 쓰면 안 된다. `items`가 `orphanRemoval = true`라 `clear()` 순간 기존
     * 엔티티가 삭제 대상이 되고, 「기존 + 새 항목」을 만들어 다시 넣으면 Hibernate가 삭제된
     * 인스턴스를 되살리는 것으로 보고 던진다.
     */
    fun addItems(newItems: List<MealItem>) {
        items.addAll(newItems)
        recalculateTotals()
    }

    /**
     * 합계는 **항상 항목에서 다시 센다** — 누적 가산이 아니다. 병합이든 수정이든 같은 식이라
     * 한쪽만 갱신되는 일이 없다. 주의 영양소 셋도 여기서 함께 센다(이 도메인에서 조용히
     * 0이 됐던 전력이 있는 자리다).
     */
    private fun recalculateTotals() {
        totalKcal = items.sumOf { it.kcal }
        carbsG = items.sumOf { it.carbsG }
        proteinG = items.sumOf { it.proteinG }
        fatG = items.sumOf { it.fatG }
        sugarG = items.sumOf { it.sugarG }
        sodiumMg = items.sumOf { it.sodiumMg }
        fiberG = items.sumOf { it.fiberG }
    }

    fun addPhoto(photo: MealPhoto) {
        photos.add(photo)
    }

    fun applyScore(score: Int?) {
        this.score = score
    }

    /** 피드백 호출이 실패해도 점수는 살린다 — 점수가 피드백보다 중요하다. */
    fun markFeedback(feedback: String?) {
        this.feedback = feedback
        this.status = if (feedback == null) AnalysisStatus.FAILED else AnalysisStatus.COMPLETED
    }

    fun markFeedbackPending() {
        this.feedback = null
        this.status = AnalysisStatus.PENDING
    }

    fun targets(): NutritionTargets =
        NutritionTargets(targetKcal, targetCarbsG, targetProteinG, targetFatG, targetSugarG, targetSodiumMg, targetFiberG)
}
