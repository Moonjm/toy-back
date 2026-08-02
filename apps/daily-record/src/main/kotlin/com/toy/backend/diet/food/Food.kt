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
    /**
     * 프랜차이즈·제조사 이름. **원본에 있는데 안 가져오고 있던 값이다.**
     *
     * 음식DB의 `업체명`(가공식품DB는 `제조사명`)이고, 없는 행은 null이다. 이 값이 없으면
     * **도미노피자 318건이 적재돼 있어도 「도미노」로 찾을 수 없다** — 식품명이
     * `피자_뉴욕 오리진 피자 오리지널 (L)`이라 브랜드가 어디에도 안 들어 있기 때문이다.
     * 음식 6,090행 중 1,946행(32%)이 브랜드를 갖는다.
     *
     * `normalizedMaker`는 검색 전용이다. **완전일치(인식 경로)에는 쓰지 않는다** — 모델이
     * 브랜드를 붙여 부르는 경우가 일정하지 않아, 이름 매칭에 섞으면 지금 동작이 흔들린다.
     */
    @Column(name = "maker", length = 200)
    var maker: String? = null,
    @Column(name = "normalized_maker", length = 200)
    var normalizedMaker: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20)")
    var dataset: FoodDataset,
    @Column(name = "serving_size_g", nullable = false)
    var servingSizeG: Double,
    /**
     * `servingSizeG`가 **원본에서 온 값인지**. false면 파서가 기본값으로 채운 것이라 근거가 없다.
     *
     * 원본이 비었거나(원재료성식품은 컬럼 자체가 없어 523행 전부) 1회 제공량으로 볼 수 없는
     * 값일 때(가공식품 26%가 포장 총중량) 기본값 200g이 들어가는데, **저장된 200과 원래 200을
     * 구분할 수 없으면 「DB가 아는 값」처럼 쓰이게 된다.** 실제로 1kg짜리 새우칩이 200g으로
     * 바뀌었고 모델이 2인분이라 해서 400g·1320kcal로 기록됐다(실제 8조각 ≈ 40g).
     *
     * false면 인식 경로가 **모델이 준 1인분 중량**을 대신 쓴다. 밀도(100g당)는 어느 쪽이든
     * 식품DB 값이 정확하므로 그대로 둔다.
     *
     * 기존 행이 있는 채로 컬럼이 붙어도 깨지지 않도록 기본값을 `true`로 둔다 — 재적재 전까지는
     * 지금과 같은 동작이다(`ddl-auto`가 NOT NULL 컬럼을 채울 수 없는 문제를 피한다).
     */
    @Column(name = "serving_size_known", nullable = false, columnDefinition = "boolean not null default true")
    var servingSizeKnown: Boolean = true,
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
    /**
     * 포화지방산·트랜스지방산·콜레스테롤. **표시 전용이다** — 검색 화면 상세 시트가 보여주기만 하고
     * `nutritionFor`·`NutritionAmount`·`MealItem` 어디에도 흐르지 않는다. 끼니에 저장하려면
     * 미매칭 항목을 채울 LLM 인식 스키마·프롬프트까지 번져서, 화면을 먼저 써 보고 판단하기로 했다.
     *
     * 원본에는 처음부터 있던 컬럼인데(음식·원재료·가공식품 셋 다) 정제 스크립트가 버렸을 뿐이다.
     *
     * `columnDefinition`에 default를 두는 이유 — `ddl-auto: update`는 **행이 있는 테이블에 NOT NULL
     * 컬럼을 붙이지 못한다.** `servingSizeKnown`이 같은 이유로 default를 달고 있다.
     */
    @Column(
        name = "saturated_fat_per_100g",
        nullable = false,
        columnDefinition = "double precision not null default 0",
    )
    var saturatedFatPer100g: Double = 0.0,
    @Column(
        name = "trans_fat_per_100g",
        nullable = false,
        columnDefinition = "double precision not null default 0",
    )
    var transFatPer100g: Double = 0.0,
    @Column(
        name = "cholesterol_mg_per_100g",
        nullable = false,
        columnDefinition = "double precision not null default 0",
    )
    var cholesterolMgPer100g: Double = 0.0,
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
