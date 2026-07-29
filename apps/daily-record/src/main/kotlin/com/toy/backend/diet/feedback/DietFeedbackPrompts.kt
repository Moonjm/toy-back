package com.toy.backend.diet.feedback

import com.toy.backend.diet.meal.Meal
import com.toy.backend.diet.profile.NutritionTargets
import com.toy.backend.diet.score.MacroStatus
import com.toy.backend.diet.score.MealScoreBasis
import kotlin.math.roundToInt

data class NutritionTotals(
    val kcal: Double,
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double,
    val sugarG: Double,
    val sodiumMg: Double,
    val fiberG: Double,
)

fun List<Meal>.totals(): NutritionTotals =
    NutritionTotals(
        kcal = sumOf { it.totalKcal },
        carbsG = sumOf { it.carbsG },
        proteinG = sumOf { it.proteinG },
        fatG = sumOf { it.fatG },
        sugarG = sumOf { it.sugarG },
        sodiumMg = sumOf { it.sodiumMg },
        fiberG = sumOf { it.fiberG },
    )

object DietFeedbackPrompts {
    /**
     * **3요소를 강제한다.** ③을 강제하지 않으면 "골고루 드세요"류로 흐른다.
     * 의학적 진단·처방은 금지 항목으로 명시한다 — 앱이 의료기기가 아니다.
     */
    const val SYSTEM_PROMPT =
        "당신은 식단 코치입니다. 아래 형식을 반드시 지켜 한국어 존댓말로 2~3문장만 쓰세요.\n" +
            "① 잘한 점 1개 ② 부족하거나 과다한 점 1개 ③ 구체적인 음식 이름이 담긴 개선 행동 1개.\n" +
            "금지: 의학적 진단·처방, 특정 질환 언급, 영양제 권유, 숫자 나열만 하는 문장, 목록 기호."

    /**
     * **하루 맥락(누적 섭취량·하루 목표·활동 에너지)을 의도적으로 넣지 않는다.** 끼니 점수가 이미
     * 「끼니는 균형, 하루는 목표 대비 총량」으로 역할을 나눠 놨는데(칼로리를 끼니 점수에 넣지 않는
     * 이유와 같다), 프롬프트만 이 경계를 넘어 하루 맥락을 실었다. 그러면 앞선 끼니를 고치거나
     * 지울 때 이 끼니의 피드백까지 같이 낡는데, 지금 구조는 직접 바뀐 끼니만 재생성하므로 아무도
     * 그걸 고쳐주지 않는다(교차 staleness). 종합은 하루 마감 피드백(`day`)의 몫으로 남긴다.
     */
    fun meal(
        meal: Meal,
        basis: MealScoreBasis?,
    ): String =
        buildString {
            appendLine("[이번 끼니] ${meal.mealType}")
            meal.items.forEach {
                appendLine(
                    "- ${it.foodName} ${it.quantityG.roundToInt()}g / ${it.kcal.roundToInt()}kcal " +
                        "(탄 ${it.carbsG.roundToInt()}g, 단 ${it.proteinG.roundToInt()}g, 지 ${it.fatG.roundToInt()}g)",
                )
            }
            appendLine(
                "이번 끼니 합계: ${meal.totalKcal.roundToInt()}kcal, 탄 ${meal.carbsG.roundToInt()}g, " +
                    "단 ${meal.proteinG.roundToInt()}g, 지 ${meal.fatG.roundToInt()}g",
            )
            appendLine("이번 끼니 균형 점수: ${meal.score ?: "산출 불가"}")
            // 근거 없이 "부족·과다를 짚어라"고만 시키면 하루 목표를 뺀 자리를 LLM이 지어낸다.
            if (basis != null) {
                appendLine("[균형 근거] ${basis.standard}")
                basis.macros.forEach {
                    appendLine("- ${it.name} ${it.percent}% (권장 ${it.rangeMin}~${it.rangeMax}%, ${it.status.toKorean()})")
                }
            }
        }

    private fun MacroStatus.toKorean(): String =
        when (this) {
            MacroStatus.OVER -> "초과"
            MacroStatus.UNDER -> "부족"
            MacroStatus.IN_RANGE -> "범위 내"
        }

    fun day(
        meals: List<Meal>,
        totals: NutritionTotals,
        targets: NutritionTargets,
        dayScore: Int,
        activeEnergyKcal: Int?,
    ): String =
        buildString {
            appendLine("[오늘 먹은 끼니]")
            meals.forEach { meal ->
                appendLine(
                    "- ${meal.mealType}: ${meal.items.joinToString(", ") { it.foodName }} " +
                        "(${meal.totalKcal.roundToInt()}kcal)",
                )
            }
            appendLine(
                "[총 섭취] ${totals.kcal.roundToInt()}kcal, 탄 ${totals.carbsG.roundToInt()}g, " +
                    "단 ${totals.proteinG.roundToInt()}g, 지 ${totals.fatG.roundToInt()}g",
            )
            appendLine("[목표] ${targets.kcal}kcal, 탄 ${targets.carbsG}g, 단 ${targets.proteinG}g, 지 ${targets.fatG}g")
            appendLine("[하루 점수] $dayScore")
            activeEnergyKcal?.let { appendLine("[활동 에너지] ${it}kcal") }
        }
}
