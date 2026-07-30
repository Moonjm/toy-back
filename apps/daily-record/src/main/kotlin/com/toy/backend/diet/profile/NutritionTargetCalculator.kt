package com.toy.backend.diet.profile

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import com.toy.backend.user.Gender
import java.time.LocalDate
import java.time.Period
import kotlin.math.roundToInt

data class NutritionTargets(
    val kcal: Int,
    val carbsG: Int,
    val proteinG: Int,
    val fatG: Int,
    val sugarG: Int,
    val sodiumMg: Int,
    val fiberG: Int,
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

    // Mifflin-St Jeor는 성인 대상 공식이라 정확한 하한을 정하는 것은 이 앱의 몫이 아니다.
    // 여기 범위는 「명백히 잘못 입력된 값」만 걷어내는 안전망이다.
    private const val MIN_AGE = 1
    private const val MAX_AGE = 120

    fun calculate(
        gender: Gender,
        birthDate: LocalDate,
        heightCm: Double,
        weightKg: Double,
        activityLevel: ActivityLevel,
        goal: DietGoal,
        today: LocalDate,
    ): NutritionTargets {
        // 미래 날짜면 나이가 음수가 되어 `-5 × age`가 BMR을 부풀리고, 지나치게 과거면 BMR이
        // 음수가 되어 목표 칼로리가 음수로 저장된다. **그 목표는 끼니 스냅샷에 복사돼 영구히
        // 남으므로** 여기서 막는다 — 요청 검증(`@Past`)은 새 입력만 걸러 이미 저장된 값은 못 잡는다.
        val age = Period.between(birthDate, today).years
        if (age !in MIN_AGE..MAX_AGE) {
            throw CustomException(ErrorCode.INVALID_REQUEST, "생년월일을 확인해 주세요")
        }
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
            sugarG = (kcal * NutrientLimitPolicy.SUGAR_ENERGY_RATIO / NutrientLimitPolicy.KCAL_PER_G_SUGAR).roundToInt(),
            sodiumMg = NutrientLimitPolicy.SODIUM_MG_LIMIT,
            fiberG = if (gender == Gender.MALE) NutrientLimitPolicy.FIBER_G_MALE else NutrientLimitPolicy.FIBER_G_FEMALE,
        )
    }
}
