package com.toy.backend.diet.daily

import com.toy.backend.diet.meal.MealResponse
import com.toy.backend.diet.score.DayScoreBasis
import java.time.LocalDate

data class DayResponse(
    val date: LocalDate,
    val dayScore: Int?,
    val scoreBasis: DayScoreBasis?,
    val feedback: String?,
    val totalKcal: Double,
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double,
    val activeEnergyKcal: Int?,
    val meals: List<MealResponse>,
    val nutrientLimits: List<NutrientLimit>,
    /**
     * 그날 항목 중 식품DB에 매칭되지 않아 LLM 추정값으로 저장된 건수. **`nutrientLimits`의 판정이
     * 얼마나 믿을 만한지를 나타낸다** — 추정이 섞인 날은 「기준 이하」가 오차 범위 안의 이야기다.
     *
     * 세 영양소가 모두 같은 항목 집합에서 오므로 `NutrientLimit`마다 반복하지 않고 하루에 한 번만
     * 내려준다. 기록이 없는 날에도 null이 아니라 0이다 — 앱이 분기를 만들지 않도록.
     */
    val estimatedItemCount: Int,
)
