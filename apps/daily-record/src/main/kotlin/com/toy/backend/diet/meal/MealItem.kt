package com.toy.backend.diet.meal

import com.toy.backend.common.entity.BaseEntity
import com.toy.backend.diet.NutritionSource
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

/**
 * 끼니 안의 개별 음식. **별도 테이블로 쪼개는 게 이 설계의 핵심이다** — 영양소를 `Meal`에 뭉쳐
 * 저장하면 ① 음식별 빈도 집계("이번 주 제육볶음 3회")가 불가능하고 ② 인식이 틀렸을 때
 * 항목 단위로 수정·재계산할 수 없다.
 */
@Entity
@Table(
    name = "meal_item",
    indexes = [
        Index(name = "idx_meal_item_meal", columnList = "meal_id"),
        Index(name = "idx_meal_item_food_code", columnList = "food_code"),
    ],
)
class MealItem(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meal_id", nullable = false)
    var meal: Meal,
    @Column(name = "food_name", nullable = false, length = 200)
    var foodName: String,
    @Column(name = "food_code", length = 30)
    var foodCode: String? = null,
    @Column(name = "quantity_g", nullable = false)
    var quantityG: Double,
    @Column(nullable = false)
    var kcal: Double,
    @Column(name = "carbs_g", nullable = false)
    var carbsG: Double,
    @Column(name = "protein_g", nullable = false)
    var proteinG: Double,
    @Column(name = "fat_g", nullable = false)
    var fatG: Double,
    @Column(name = "sugar_g", nullable = false)
    var sugarG: Double = 0.0,
    @Column(name = "sodium_mg", nullable = false)
    var sodiumMg: Double = 0.0,
    @Column(name = "fiber_g", nullable = false)
    var fiberG: Double = 0.0,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20)")
    var source: NutritionSource,
) : BaseEntity()

/**
 * 다른 끼니로 **베껴 붙일** 사본. 옮기지 않는 이유는 `Meal.addItems` 주석과 같다 — `items`가
 * `orphanRemoval = true`라 원본 컬렉션에서 빼는 순간 그 행이 삭제 대상이 되고, 같은 인스턴스를
 * 다른 끼니에 붙이면 Hibernate가 「삭제된 엔티티를 다시 저장」으로 보고 던진다.
 *
 * **`meal`만 바꾸고 나머지는 전부 그대로 옮긴다.** 「이름·수량·탄단지」만 챙기면 조용히 새는
 * 것이 있다 — `source`가 떨어지면 하루 응답의 `estimatedItemCount`가 「추정이 섞였다」 표시를
 * 잃고, `foodCode`가 떨어지면 음식 빈도 집계에서 빠지며, 당·나트륨·식이섬유는 이 도메인에서
 * 조용히 0이 됐던 전력이 있는 자리다.
 */
fun MealItem.copyTo(meal: Meal): MealItem =
    MealItem(
        meal = meal,
        foodName = foodName,
        foodCode = foodCode,
        quantityG = quantityG,
        kcal = kcal,
        carbsG = carbsG,
        proteinG = proteinG,
        fatG = fatG,
        sugarG = sugarG,
        sodiumMg = sodiumMg,
        fiberG = fiberG,
        source = source,
    )
