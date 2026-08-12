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
    // 미매칭 항목도 LLM 추정값이 들어온다. 기본값 0.0은 직접 입력 경로를 위한 것이지 추정 항목을
    // 위한 게 아니다 — 추정 항목을 0으로 두면 하루 나트륨 합계가 늘 실제보다 작아진다.
    val sugarG: Double = 0.0,
    val sodiumMg: Double = 0.0,
    val fiberG: Double = 0.0,
    val source: NutritionSource,
)
