package com.toy.backend.diet.feedback

import com.toy.backend.diet.meal.Meal
import com.toy.backend.diet.profile.NutritionTargets
import com.toy.backend.diet.score.MacroStatus
import com.toy.backend.diet.score.MealScore
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
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
     * `2026-08-01 (토)`. **요일까지 넣는다** — 주말 과식 같은 요일 효과는 날짜만으로는 안 보인다.
     */
    private val PROMPT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd (E)", Locale.KOREAN)

    /**
     * **3요소를 강제한다.** ③을 강제하지 않으면 "골고루 드세요"류로 흐른다.
     * 의학적 진단·처방은 금지 항목으로 명시한다 — 앱이 의료기기가 아니다.
     *
     * **다만 ①은 「없으면 건너뛴다」로 열어 둔다.** 실기동에서 튀긴 치킨을 먹은 날에
     * "튀긴 음식 대신 구운 닭 가슴살을 선택하신 점은 훌륭했습니다"가 나왔다 — 칭찬할 것이
     * 없는 식단에서 모델이 **먹지 않은 음식을 지어내** 실제와 정반대를 말한 것이다.
     * 격려가 기록을 이어가게 만든다는 게 ①의 이유였지만, 사실과 반대되는 칭찬은 그 신뢰를
     * 오히려 깎는다. 「먹은 것 안에서만 고르라」를 함께 못박는다.
     */
    const val SYSTEM_PROMPT =
        "당신은 식단 코치입니다. 아래 형식을 지켜 한국어 존댓말로 2~3문장만 쓰세요.\n" +
            "① 잘한 점 1개 ② 부족하거나 과다한 점 1개 ③ 구체적인 음식 이름이 담긴 개선 행동 1개.\n" +
            "①은 실제로 먹은 음식에서만 고르세요. 칭찬할 것이 없으면 ①을 빼고 ②③만 쓰세요 — " +
            "먹지 않은 음식을 먹었다고 하거나 없는 장점을 지어내면 안 됩니다.\n" +
            "금지: 의학적 진단·처방, 특정 질환 언급, 영양제 권유, 숫자 나열만 하는 문장, 목록 기호."

    /**
     * **하루 맥락(누적 섭취량·하루 목표·활동 에너지)을 의도적으로 넣지 않는다.** 끼니 점수가 이미
     * 「끼니는 균형, 하루는 목표 대비 총량」으로 역할을 나눠 놨는데(칼로리를 끼니 점수에 넣지 않는
     * 이유와 같다), 프롬프트만 이 경계를 넘어 하루 맥락을 실었다. 그러면 앞선 끼니를 고치거나
     * 지울 때 이 끼니의 피드백까지 같이 낡는데, 지금 구조는 직접 바뀐 끼니만 재생성하므로 아무도
     * 그걸 고쳐주지 않는다(교차 staleness). 종합은 하루 마감 피드백(`day`)의 몫으로 남긴다.
     *
     * **점수와 근거를 [scored] 하나로 받는다 — 저장된 `Meal.score` 컬럼을 읽지 않는다.**
     * 점수는 컬럼에서, 근거는 그때 재계산해서 오면 한 블록 안에서 둘이 어긋난다. 감점 기울기를
     * 2.0 → 1.0으로 바꿨을 때 저장된 점수를 백필하지 않았으므로, 그 이전 끼니는 지금도 컬럼 값이
     * 낡아 있다 — 사용자가 화면에서 보는 점수(`MealDtos.toResponse`도 함께 재계산한다)와
     * LLM이 설명하는 점수가 달라진다.
     */
    fun meal(
        meal: Meal,
        scored: MealScore,
    ): String =
        buildString {
            appendLine("[이번 끼니] ${meal.mealType.label}")
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
            appendLine("이번 끼니 균형 점수: ${scored.score ?: "산출 불가"}")
            // 근거 없이 "부족·과다를 짚어라"고만 시키면 하루 목표를 뺀 자리를 LLM이 지어낸다.
            scored.basis?.let { basis ->
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
        date: LocalDate,
        meals: List<Meal>,
        totals: NutritionTotals,
        targets: NutritionTargets,
        dayScore: Int,
        activeEnergyKcal: Int?,
    ): String =
        buildString {
            appendLine("[${date.format(PROMPT_DATE)} 먹은 끼니]")
            meals.forEach { meal ->
                appendLine(
                    "- ${meal.mealType.label}: ${meal.items.joinToString(", ") { it.foodName }} " +
                        "(${meal.totalKcal.roundToInt()}kcal)",
                )
            }
            appendLine(
                "[총 섭취] ${totals.kcal.roundToInt()}kcal, 탄 ${totals.carbsG.roundToInt()}g, " +
                    "단 ${totals.proteinG.roundToInt()}g, 지 ${totals.fatG.roundToInt()}g",
            )
            appendLine("[목표] ${targets.kcal}kcal, 탄 ${targets.carbsG}g, 단 ${targets.proteinG}g, 지 ${targets.fatG}g")
            // 기준을 함께 실어야 LLM이 "나트륨 2,610mg"만 보고 많은지 적은지 스스로 판단하지 않는다.
            appendLine(
                "[주의 영양소] 나트륨 ${totals.sodiumMg.roundToInt()}mg (기준 ${targets.sodiumMg}mg 이하), " +
                    "식이섬유 ${totals.fiberG.roundToInt()}g (기준 ${targets.fiberG}g 이상), " +
                    "당류 ${totals.sugarG.roundToInt()}g (기준 ${targets.sugarG}g 이하)",
            )
            appendLine("[하루 점수] $dayScore")
            activeEnergyKcal?.let { appendLine("[활동 에너지] ${it}kcal") }
        }
}
