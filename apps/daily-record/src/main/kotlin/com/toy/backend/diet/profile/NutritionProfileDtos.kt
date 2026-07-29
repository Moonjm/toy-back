package com.toy.backend.diet.profile

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin

data class NutritionProfileRequest(
    @field:DecimalMin("100.0") @field:DecimalMax("250.0")
    val heightCm: Double,
    @field:DecimalMin("20.0") @field:DecimalMax("300.0")
    val weightKg: Double,
    val activityLevel: ActivityLevel,
    val goal: DietGoal,
)

data class WeightUpdateRequest(
    @field:DecimalMin("20.0") @field:DecimalMax("300.0")
    val weightKg: Double,
)

data class NutritionProfileResponse(
    val heightCm: Double,
    val weightKg: Double,
    val activityLevel: ActivityLevel,
    val goal: DietGoal,
    val targetKcal: Int,
    val targetCarbsG: Int,
    val targetProteinG: Int,
    val targetFatG: Int,
)

fun NutritionProfile.toResponse(): NutritionProfileResponse =
    NutritionProfileResponse(
        heightCm = heightCm,
        weightKg = weightKg,
        activityLevel = activityLevel,
        goal = goal,
        targetKcal = targetKcal,
        targetCarbsG = targetCarbsG,
        targetProteinG = targetProteinG,
        targetFatG = targetFatG,
    )
