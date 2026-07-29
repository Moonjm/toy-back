package com.toy.backend.diet.food

import com.toy.backend.common.entity.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Index
import jakarta.persistence.Table

object FoodPolicy {
    /**
     * `1인(회)분량 참고량`이 비어 있는 행과, 식품DB에 없어 LLM 추정으로 넘어간 음식의 기본 1인분.
     * **실제 CSV의 결측률을 확인한 뒤 조정해야 하는 초기 추정치다.**
     */
    const val DEFAULT_SERVING_SIZE_G = 200.0
}

/** 적재 출처. 매칭 규칙을 가르는 축이라 값이 늘어날 일이 거의 없다. */
enum class FoodDataset { DISH, PROCESSED }

/** 식약처 `전국통합식품영양성분정보(음식)` 표준데이터와 가공식품DB를 100g 기준으로 정규화해 적재한 표. */
@Entity
@Table(
    name = "foods",
    indexes = [
        // 완전일치는 (dataset, normalized_name) 인덱스 조회라 30만 행이어도 빠르다.
        // 부분일치(LIKE '%x%')는 인덱스를 못 쓰므로 DISH 6천 행으로만 제한해서 감당한다.
        Index(name = "idx_foods_dataset_normalized_name", columnList = "dataset, normalized_name"),
    ],
)
class Food(
    @Column(nullable = false, length = 30, unique = true)
    var code: String,
    @Column(nullable = false, length = 200)
    var name: String,
    @Column(name = "normalized_name", nullable = false, length = 200)
    var normalizedName: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20)")
    var dataset: FoodDataset,
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
