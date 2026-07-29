package com.toy.backend.diet.score

enum class MacroStatus { UNDER, IN_RANGE, OVER }

/**
 * status와 penalty는 **서버가 계산해 내려준다.** 앱이 percent와 범위만 받아 판정하면 감점 규칙이
 * 두 곳에 생기고, 서버가 기울기를 튜닝했을 때 앱 표시와 실제 점수가 어긋난다.
 */
data class MacroBasis(
    val name: String,
    val percent: Double,
    val rangeMin: Int,
    val rangeMax: Int,
    val status: MacroStatus,
    val penalty: Double,
)

data class MealScoreBasis(
    val standard: String,
    val macros: List<MacroBasis>,
)

data class MealScore(
    val score: Int?,
    val basis: MealScoreBasis?,
)
