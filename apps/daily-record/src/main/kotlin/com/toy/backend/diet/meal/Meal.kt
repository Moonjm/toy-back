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

enum class MealType { BREAKFAST, LUNCH, DINNER, SNACK }

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

    /** 항목은 항상 전체 교체다 — 확정과 수정이 같은 경로를 쓰도록 편집 로직을 한 벌만 만든다. */
    fun replaceItems(newItems: List<MealItem>) {
        items.clear()
        items.addAll(newItems)
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
