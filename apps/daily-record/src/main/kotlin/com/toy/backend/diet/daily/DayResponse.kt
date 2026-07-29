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
)
