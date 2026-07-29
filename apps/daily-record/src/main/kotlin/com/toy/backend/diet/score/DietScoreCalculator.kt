package com.toy.backend.diet.score

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
}
