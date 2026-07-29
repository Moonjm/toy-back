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
    name = "meal_items",
    indexes = [
        Index(name = "idx_meal_items_meal", columnList = "meal_id"),
        Index(name = "idx_meal_items_food_code", columnList = "food_code"),
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
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20)")
    var source: NutritionSource,
) : BaseEntity()
