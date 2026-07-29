package com.toy.backend.diet.analysis

import com.toy.backend.diet.NutritionSource

/**
 * `MealAnalysis.resultJson`에 통째로 담기는 구조. **자식 테이블로 쪼개지 않는다** —
 * 확인 전 임시 데이터라 이걸로 질의할 일이 없고, 확정되면 `MealItem`으로 옮겨가며 통째로 버려진다.
 */
data class AnalysisResult(
    val photos: List<AnalyzedPhoto>,
)

data class AnalyzedPhoto(
    val fileId: Long,
    val failed: Boolean = false,
    val items: List<AnalyzedItem> = emptyList(),
)

data class AnalyzedItem(
    val foodName: String,
    val foodCode: String?,
    val quantityG: Double,
    val kcal: Double,
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double,
    // LLM 추정 항목은 0.0으로 둔다 — LLM에게 나트륨 등을 추정시키지 않는다는 이 설계의 전제 때문이다.
    val sugarG: Double = 0.0,
    val sodiumMg: Double = 0.0,
    val fiberG: Double = 0.0,
    val source: NutritionSource,
)
