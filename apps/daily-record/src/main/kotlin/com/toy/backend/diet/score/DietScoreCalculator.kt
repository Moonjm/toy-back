package com.toy.backend.diet.score

import com.toy.backend.diet.profile.NutritionTargets
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 점수는 전부 여기서만 계산한다. LLM에게 점수를 묻지 않는 이유가 결정성과 테스트 가능성이므로,
 * 계산이 여러 곳으로 흩어지면 그 이점이 사라진다.
 */
object DietScoreCalculator {
    /**
     * 끼니 점수 — 개인 목표가 아니라 **KDRIs 범위**만 본다. 개인 목표를 점으로 놓고 편차를 감점하면
     * 권장 범위 한가운데인 식사도 감점되어 사용자에게 설명할 수 없다. 칼로리는 넣지 않는다 —
     * 아침을 가볍게 먹은 것을 감점하면 안 되고, 총량은 하루 단위에서만 평가한다.
     */
    fun scoreMeal(
        carbsG: Double,
        proteinG: Double,
        fatG: Double,
    ): MealScore {
        // 비율의 분모는 Meal.totalKcal이 아니라 매크로에서 역산한 값이다 — 식품DB의 kcal은
        // 탄단지 합산과 정확히 일치하지 않아(알코올·식이섬유·측정 오차) 세 비율의 합이 100%가 되지 않는다.
        val macroKcal =
            carbsG * DietScorePolicy.KCAL_PER_G_CARBS +
                proteinG * DietScorePolicy.KCAL_PER_G_PROTEIN +
                fatG * DietScorePolicy.KCAL_PER_G_FAT
        if (macroKcal <= 0.0) return MealScore(score = null, basis = null)

        val macros =
            listOf(
                basisOf(
                    DietScorePolicy.CARBS_LABEL,
                    carbsG * DietScorePolicy.KCAL_PER_G_CARBS / macroKcal * 100,
                    DietScorePolicy.CARBS_RANGE,
                ),
                basisOf(
                    DietScorePolicy.PROTEIN_LABEL,
                    proteinG * DietScorePolicy.KCAL_PER_G_PROTEIN / macroKcal * 100,
                    DietScorePolicy.PROTEIN_RANGE,
                ),
                basisOf(
                    DietScorePolicy.FAT_LABEL,
                    fatG * DietScorePolicy.KCAL_PER_G_FAT / macroKcal * 100,
                    DietScorePolicy.FAT_RANGE,
                ),
            )

        val score = max(0.0, 100.0 - macros.sumOf { it.penalty }).roundToInt()
        return MealScore(score = score, basis = MealScoreBasis(DietScorePolicy.STANDARD_NAME, macros))
    }

    private fun basisOf(
        name: String,
        rawPercent: Double,
        range: ClosedFloatingPointRange<Double>,
    ): MacroBasis {
        // 표시값과 감점 근거가 어긋나지 않도록 소수 첫째 자리로 맞춘 값을 그대로 쓴다.
        val percent = (rawPercent * 10).roundToInt() / 10.0
        val excess = max(0.0, max(range.start - percent, percent - range.endInclusive))
        val status =
            when {
                percent < range.start -> MacroStatus.UNDER
                percent > range.endInclusive -> MacroStatus.OVER
                else -> MacroStatus.IN_RANGE
            }
        return MacroBasis(
            name = name,
            percent = percent,
            rangeMin = range.start.toInt(),
            rangeMax = range.endInclusive.toInt(),
            status = status,
            penalty = excess * DietScorePolicy.MEAL_PENALTY_PER_PERCENT,
        )
    }

    /**
     * 하루 점수 — 칼로리(총량) 40% + 매크로(구성) 60%. 목표는 호출자가 넘긴다.
     * 호출자(`DailyDietService`)는 **현재 프로필이 아니라 그날 첫 `Meal`의 스냅샷**을 넘겨야
     * 몸무게를 갱신했을 때 과거 점수가 흔들리지 않는다.
     *
     * 각 요소 점수를 먼저 Int로 반올림한 뒤 가중 합산한다 — 앱이 근거에 실린 숫자만으로
     * 최종 점수를 그대로 재현할 수 있어야 하기 때문이다.
     */
    fun scoreDay(
        intakeKcal: Double,
        carbsG: Double,
        proteinG: Double,
        fatG: Double,
        targets: NutritionTargets,
    ): DayScore {
        val calorieRatio = ratioOf(intakeKcal, targets.kcal)
        val calorieScore =
            when {
                calorieRatio < DietScorePolicy.CALORIE_TOLERANCE_LOW -> {
                    penalized(DietScorePolicy.CALORIE_TOLERANCE_LOW - calorieRatio)
                }

                calorieRatio > DietScorePolicy.CALORIE_TOLERANCE_HIGH -> {
                    penalized(calorieRatio - DietScorePolicy.CALORIE_TOLERANCE_HIGH)
                }

                else -> {
                    100
                }
            }

        val macros =
            listOf(
                macroBasis(DietScorePolicy.CARBS_LABEL, carbsG, targets.carbsG, penalizeOver = true),
                // 단백질만 초과를 감점하지 않는다 — 단백질 과다는 실질적 문제가 아니어서,
                // 감점하면 "고기를 충분히 먹었더니 점수가 깎이는" 잘못된 신호를 준다.
                macroBasis(DietScorePolicy.PROTEIN_LABEL, proteinG, targets.proteinG, penalizeOver = false),
                macroBasis(DietScorePolicy.FAT_LABEL, fatG, targets.fatG, penalizeOver = true),
            )

        val macroScore = macros.sumOf { it.score }.toDouble() / macros.size
        val dayScore =
            (DietScorePolicy.CALORIE_WEIGHT * calorieScore + DietScorePolicy.MACRO_WEIGHT * macroScore).roundToInt()

        return DayScore(
            score = dayScore,
            basis =
                DayScoreBasis(
                    standard = DietScorePolicy.DAY_STANDARD_NAME,
                    calorie =
                        CalorieBasis(
                            intakeKcal = intakeKcal,
                            targetKcal = targets.kcal,
                            ratio = calorieRatio,
                            calorieScore = calorieScore,
                        ),
                    macros = macros,
                    calorieWeight = DietScorePolicy.CALORIE_WEIGHT,
                    macroWeight = DietScorePolicy.MACRO_WEIGHT,
                ),
        )
    }

    private fun macroBasis(
        name: String,
        intakeG: Double,
        targetG: Int,
        penalizeOver: Boolean,
    ): MacroAmountBasis {
        val ratio = ratioOf(intakeG, targetG)
        val score =
            when {
                ratio < 1.0 -> {
                    (100 * ratio).roundToInt()
                }

                penalizeOver && ratio > DietScorePolicy.MACRO_OVER_TOLERANCE -> {
                    penalized(ratio - DietScorePolicy.MACRO_OVER_TOLERANCE)
                }

                else -> {
                    100
                }
            }
        return MacroAmountBasis(name = name, intakeG = intakeG, targetG = targetG, ratio = ratio, score = score)
    }

    private fun penalized(excessRatio: Double): Int = max(0.0, 100.0 - DietScorePolicy.PENALTY_SLOPE * excessRatio).roundToInt()

    /** 목표는 프로필 계산 결과라 항상 양수지만, 0으로 나누는 사고는 막아 둔다. */
    private fun ratioOf(
        intake: Double,
        target: Int,
    ): Double = intake / max(1, target).toDouble()
}
