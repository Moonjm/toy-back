package com.toy.backend.diet.daily

import com.toy.backend.diet.feedback.NutritionTotals
import com.toy.backend.diet.profile.NutritionTargets
import java.util.Locale

enum class NutrientStatus { OK, WARN }

/**
 * **점수에 들어가지 않으므로 `penalty`가 없다.** 점수는 여전히 탄단지 비율만 보고 매겨지며,
 * 이 판정은 「기준 대비 표시」일 뿐이다. 앱이 이것을 감점 요인처럼 보이게 하면 안 된다.
 *
 * `standardText`는 사람이 읽는 문구를 그대로 담는다 — 기준이 개정돼도 앱 배포 없이 따라간다.
 */
data class NutrientLimit(
    val name: String,
    val intake: Double,
    val unit: String,
    val standardText: String,
    val status: NutrientStatus,
)

/**
 * 항목마다 문제가 되는 방향이 다르다 — 나트륨·당류는 초과가, 식이섬유는 미달이 문제다.
 * 그 방향을 앱이 알 필요는 없어서 `OK`/`WARN` 둘로만 내려준다.
 */
object NutrientLimitEvaluator {
    fun evaluate(
        totals: NutritionTotals,
        targets: NutritionTargets,
    ): List<NutrientLimit> =
        listOf(
            upperLimit("나트륨", totals.sodiumMg, targets.sodiumMg, "mg"),
            lowerTarget("식이섬유", totals.fiberG, targets.fiberG, "g"),
            upperLimit("당류", totals.sugarG, targets.sugarG, "g"),
        )

    /** 경계는 넘지 않은 것으로 본다 — 「이하」이므로 같은 값은 OK다. */
    private fun upperLimit(
        name: String,
        intake: Double,
        standard: Int,
        unit: String,
    ) = NutrientLimit(
        name = name,
        intake = intake,
        unit = unit,
        standardText = "${format(standard)}$unit 이하",
        status = if (intake > standard) NutrientStatus.WARN else NutrientStatus.OK,
    )

    private fun lowerTarget(
        name: String,
        intake: Double,
        standard: Int,
        unit: String,
    ) = NutrientLimit(
        name = name,
        intake = intake,
        unit = unit,
        standardText = "${format(standard)}$unit 이상",
        status = if (intake < standard) NutrientStatus.WARN else NutrientStatus.OK,
    )

    // JVM 기본 Locale에 맡기면 독일 로케일 등에서 "2.300mg"처럼 자릿수 구분자가 바뀐다.
    // 이 문자열이 앱에 그대로 내려가 화면에 표시되므로 고정한다.
    private fun format(value: Int): String = String.format(Locale.KOREA, "%,d", value)
}
