package com.toy.backend.diet.profile

import com.toy.backend.user.Gender
import java.time.LocalDate
import java.time.Period
import kotlin.math.roundToInt

data class NutritionTargets(
    val kcal: Int,
    val carbsG: Int,
    val proteinG: Int,
    val fatG: Int,
)

/**
 * 목표 섭취량 산출. BMR은 Mifflin-St Jeor(1990) 공식이고, 활동 계수는 PAL 관례값,
 * 매크로 배분은 KDRIs 에너지적정비율 안에서 고른 값이다(`DietGoal` 주석 참조).
 *
 * 매크로 g은 **반올림된 kcal**에서 다시 계산한다 — 사용자에게 보이는 목표 칼로리와
 * 매크로 목표가 서로 맞아야 "2509kcal의 55%가 탄수화물 345g"이라고 설명할 수 있다.
 */
object NutritionTargetCalculator {
    private const val KCAL_PER_G_CARBS = 4.0
    private const val KCAL_PER_G_PROTEIN = 4.0
    private const val KCAL_PER_G_FAT = 9.0

    fun calculate(
        gender: Gender,
        birthDate: LocalDate,
        heightCm: Double,
        weightKg: Double,
        activityLevel: ActivityLevel,
        goal: DietGoal,
        today: LocalDate,
    ): NutritionTargets {
        val age = Period.between(birthDate, today).years
        val bmr =
            when (gender) {
                Gender.MALE -> 10 * weightKg + 6.25 * heightCm - 5 * age + 5
                Gender.FEMALE -> 10 * weightKg + 6.25 * heightCm - 5 * age - 161
            }
        val kcal = (bmr * activityLevel.factor * goal.calorieFactor).roundToInt()
        return NutritionTargets(
            kcal = kcal,
            carbsG = (kcal * goal.carbsPercent / 100.0 / KCAL_PER_G_CARBS).roundToInt(),
            proteinG = (kcal * goal.proteinPercent / 100.0 / KCAL_PER_G_PROTEIN).roundToInt(),
            fatG = (kcal * goal.fatPercent / 100.0 / KCAL_PER_G_FAT).roundToInt(),
        )
    }
}
