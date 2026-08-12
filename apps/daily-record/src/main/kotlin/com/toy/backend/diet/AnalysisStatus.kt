package com.toy.backend.diet

/**
 * `MealAnalysis`에서는 *인식* 진행 상태, `Meal`에서는 *피드백 생성* 상태를 뜻한다.
 * 값 집합과 전이 모양이 같아 따로 만들 이유가 없다.
 */
enum class AnalysisStatus { PENDING, COMPLETED, FAILED }
