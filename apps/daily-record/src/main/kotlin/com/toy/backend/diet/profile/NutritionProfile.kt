package com.toy.backend.diet.profile

import com.toy.backend.common.entity.BaseEntity
import com.toy.backend.user.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

/** PAL(신체활동수준) 관례값 1.2~1.9 — FAO/WHO/UNU 에너지 요구량 보고서 계열의 통용값. */
enum class ActivityLevel(
    val factor: Double,
) {
    SEDENTARY(1.2),
    LIGHT(1.375),
    MODERATE(1.55),
    ACTIVE(1.725),
    VERY_ACTIVE(1.9),
}

/**
 * 매크로 비율은 2025 한국인 영양소 섭취기준(KDRIs) 에너지적정비율(탄 50~65 · 단 10~20 · 지 15~30)
 * 안에서 목표별로 고른 값이다. 세 비율의 합이 100이어야 해서 범위 중앙값을 그대로 쓸 수 없다.
 * 칼로리 계수(0.85/1.0/1.1)는 자체 설정값으로 공개 근거가 없다.
 */
enum class DietGoal(
    val calorieFactor: Double,
    val carbsPercent: Int,
    val proteinPercent: Int,
    val fatPercent: Int,
) {
    LOSE(0.85, 50, 20, 30),
    MAINTAIN(1.0, 55, 15, 30),
    GAIN(1.1, 60, 15, 25),
}

@Entity
@Table(
    name = "nutrition_profiles",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_nutrition_profiles_user", columnNames = ["user_id"]),
    ],
)
class NutritionProfile(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,
    @Column(name = "height_cm", nullable = false)
    var heightCm: Double,
    @Column(name = "weight_kg", nullable = false)
    var weightKg: Double,
    // columnDefinition 명시로 enum CHECK 제약 생성을 막는다 (ddl-auto:update가 제약을 갱신하지 못함)
    @Enumerated(EnumType.STRING)
    @Column(name = "activity_level", nullable = false, columnDefinition = "varchar(20)")
    var activityLevel: ActivityLevel,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(10)")
    var goal: DietGoal,
    @Column(name = "target_kcal", nullable = false)
    var targetKcal: Int = 0,
    @Column(name = "target_carbs_g", nullable = false)
    var targetCarbsG: Int = 0,
    @Column(name = "target_protein_g", nullable = false)
    var targetProteinG: Int = 0,
    @Column(name = "target_fat_g", nullable = false)
    var targetFatG: Int = 0,
) : BaseEntity() {
    fun updateDetails(
        heightCm: Double,
        weightKg: Double,
        activityLevel: ActivityLevel,
        goal: DietGoal,
    ) {
        this.heightCm = heightCm
        this.weightKg = weightKg
        this.activityLevel = activityLevel
        this.goal = goal
    }

    /** 몸무게는 매일 갱신된다 — 키·활동량·목표를 함께 보내게 하면 클라이언트가 낡은 값을 되돌려 쓴다. */
    fun updateWeight(weightKg: Double) {
        this.weightKg = weightKg
    }

    fun applyTargets(targets: NutritionTargets) {
        this.targetKcal = targets.kcal
        this.targetCarbsG = targets.carbsG
        this.targetProteinG = targets.proteinG
        this.targetFatG = targets.fatG
    }

    fun targets(): NutritionTargets = NutritionTargets(targetKcal, targetCarbsG, targetProteinG, targetFatG)
}
