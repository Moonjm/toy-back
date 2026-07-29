package com.toy.backend.diet.food

data class FoodResponse(
    val code: String,
    val name: String,
    /** 앱이 「가공식품」임을 표시할 수 있게 함께 내려준다. */
    val dataset: FoodDataset,
    val servingSizeG: Double,
    val kcalPer100g: Double,
    val carbsPer100g: Double,
    val proteinPer100g: Double,
    val fatPer100g: Double,
)

fun Food.toResponse(): FoodResponse =
    FoodResponse(
        code = code,
        name = name,
        dataset = dataset,
        servingSizeG = servingSizeG,
        kcalPer100g = kcalPer100g,
        carbsPer100g = carbsPer100g,
        proteinPer100g = proteinPer100g,
        fatPer100g = fatPer100g,
    )
