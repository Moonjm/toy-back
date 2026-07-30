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

    /**
     * 이 값을 넘는 `1인분`은 믿지 않고 결측으로 본다.
     *
     * **원본의 `1인(회)분량 참고량` 컬럼이 2026-07-28 판에서 사라져 `식품중량`으로 폴백하는데,
     * 가공식품의 식품중량은 1회 제공량이 아니라 포장 총중량이다.** 냉동 해쉬브라운 한 봉지가
     * 640g, 치킨볼 2kg, 콜라 1.8L로 들어온다. 그대로 쓰면 해쉬브라운 한 조각이 640g·1069kcal로
     * 기록돼 하루 총량과 나트륨 판정이 통째로 무너진다.
     *
     * 실측 분포 — 가공식품 306,293행의 중앙값은 208g으로 멀쩡하지만 평균이 5,916g이고
     * 26%가 500g을, 12%가 1kg을 넘는다. 한 사람이 한 번에 먹는 양으로 500g을 넘는 경우는
     * 사실상 없어서 여기를 경계로 잡았다.
     */
    const val MAX_TRUSTED_SERVING_SIZE_G = 500.0
}

/**
 * 적재 출처. 매칭 규칙을 가르는 축이다.
 *
 * - `DISH` 조리된 음식(제육볶음·김치찌개) — 사진에 가장 흔히 찍히는 것
 * - `RAW` 원재료성식품(복숭아·바나나·계란) — 손질만 해서 그대로 먹는 것
 * - `PROCESSED` 가공식품(브랜드 제품) — 30만 행이라 완전일치에만 참여시킨다
 */
enum class FoodDataset { DISH, RAW, PROCESSED }

/** 식약처 `전국통합식품영양성분정보(음식)` 표준데이터와 가공식품DB를 100g 기준으로 정규화해 적재한 표. */
@Entity
@Table(
    name = "food",
    indexes = [
        // 완전일치는 (dataset, normalized_name) 인덱스 조회라 30만 행이어도 빠르다.
        // 부분일치(LIKE '%x%')는 인덱스를 못 쓰므로 DISH 6천 행으로만 제한해서 감당한다.
        Index(name = "idx_food_dataset_normalized_name", columnList = "dataset, normalized_name"),
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
    @Column(name = "sugar_per_100g", nullable = false)
    var sugarPer100g: Double,
    @Column(name = "sodium_mg_per_100g", nullable = false)
    var sodiumMgPer100g: Double,
    @Column(name = "fiber_per_100g", nullable = false)
    var fiberPer100g: Double,
) : BaseEntity()

data class NutritionAmount(
    val quantityG: Double,
    val kcal: Double,
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double,
    val sugarG: Double,
    val sodiumMg: Double,
    val fiberG: Double,
)

/**
 * LLM이 주는 `portion`은 1인분 대비 배수다(0.5 = 반 인분). 이를 g으로 바꿔 100g당 값에 곱한다.
 *
 * `servingSize`를 따로 받을 수 있게 열어 둔 것은 **1인분 중량을 식품DB가 모르는 데이터셋**
 * 때문이다 — `RAW`(원재료성식품)는 원본에 1인분 컬럼이 아예 없어 523행 전부 기본값 200g이다.
 * 사과·복숭아는 우연히 맞지만 달걀 한 개(50g)가 4배, 잣 한 줌(15g)이 13배로 잡힌다.
 * 그때는 **밀도(100g당)만 식품DB에서 쓰고 양은 모델이 준 1인분 중량**을 넘긴다.
 */
fun Food.nutritionFor(
    portion: Double,
    servingSize: Double = servingSizeG,
): NutritionAmount {
    val quantityG = servingSize * portion
    val ratio = quantityG / 100.0
    return NutritionAmount(
        quantityG = quantityG,
        kcal = kcalPer100g * ratio,
        carbsG = carbsPer100g * ratio,
        proteinG = proteinPer100g * ratio,
        fatG = fatPer100g * ratio,
        sugarG = sugarPer100g * ratio,
        sodiumMg = sodiumMgPer100g * ratio,
        fiberG = fiberPer100g * ratio,
    )
}
