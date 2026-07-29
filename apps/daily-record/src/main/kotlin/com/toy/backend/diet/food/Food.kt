package com.toy.backend.diet.food

import com.toy.backend.common.entity.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Table

object FoodPolicy {
    /**
     * `1인(회)분량 참고량`이 비어 있는 행과, 식품DB에 없어 LLM 추정으로 넘어간 음식의 기본 1인분.
     * **실제 CSV의 결측률을 확인한 뒤 조정해야 하는 초기 추정치다.**
     */
    const val DEFAULT_SERVING_SIZE_G = 200.0
}

/** 식약처 `전국통합식품영양성분정보(음식)` 표준데이터를 100g 기준으로 정규화해 적재한 표. */
@Entity
@Table(
    name = "foods",
    indexes = [
        Index(name = "idx_foods_normalized_name", columnList = "normalized_name"),
    ],
)
class Food(
    @Column(nullable = false, length = 30, unique = true)
    var code: String,
    @Column(nullable = false, length = 200)
    var name: String,
    @Column(name = "normalized_name", nullable = false, length = 200)
    var normalizedName: String,
    @Column(name = "serving_size_g", nullable = false)
    var servingSizeG: Double,
    @Column(name = "kcal_per_100g", nullable = false)
    var kcalPer100g: Double,
    @Column(name = "carbs_per_100g", nullable = false)
    var carbsPer100g: Double,
    @Column(name = "protein_per_100g", nullable = false)
    var proteinPer100g: Double,
    @Column(name = "fat_per_100g", nullable = false)
    var fatPer100g: Double,
) : BaseEntity()

data class NutritionAmount(
    val quantityG: Double,
    val kcal: Double,
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double,
)

/** LLM이 주는 `portion`은 1인분 대비 배수다(0.5 = 반 인분). 이를 g으로 바꿔 100g당 값에 곱한다. */
fun Food.nutritionFor(portion: Double): NutritionAmount {
    val quantityG = servingSizeG * portion
    val ratio = quantityG / 100.0
    return NutritionAmount(
        quantityG = quantityG,
        kcal = kcalPer100g * ratio,
        carbsG = carbsPer100g * ratio,
        proteinG = proteinPer100g * ratio,
        fatG = fatPer100g * ratio,
    )
}
