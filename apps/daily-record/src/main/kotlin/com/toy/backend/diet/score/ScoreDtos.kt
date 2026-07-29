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

data class CalorieBasis(
    val intakeKcal: Double,
    val targetKcal: Int,
    val ratio: Double,
    val calorieScore: Int,
)

data class MacroAmountBasis(
    val name: String,
    val intakeG: Double,
    val targetG: Int,
    val ratio: Double,
    val score: Int,
)

/**
 * 하루 점수 근거. 끼니 근거와 달리 `standard`가 자체 기준이라고 밝힌다 —
 * 가중치와 기울기는 우리가 정한 값이라 국가 기준과 같은 무게로 제시하면 안 된다.
 */
data class DayScoreBasis(
    val standard: String,
    val calorie: CalorieBasis,
    val macros: List<MacroAmountBasis>,
    val calorieWeight: Double,
    val macroWeight: Double,
)

data class DayScore(
    val score: Int,
    val basis: DayScoreBasis,
)
