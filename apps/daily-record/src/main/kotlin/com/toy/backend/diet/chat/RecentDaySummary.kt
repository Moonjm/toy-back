package com.toy.backend.diet.chat

import com.toy.backend.diet.meal.MealType
import java.time.LocalDate

/**
 * 직전 7일 한 줄치. **렌더링에 필요한 것만 담는다** — 수량·매크로는 일부러 없다. 7일치에
 * 항목별 g·kcal·균형 근거까지 실으면 기준일 상세와 크기가 비슷해진다.
 *
 * [warnings]는 방향 단어(「초과」·「부족」)를 새로 만들지 않고 `NutrientLimit`의 값을 그대로
 * 조립한 문자열이다 — 「나트륨 4200mg(기준 2300mg 이하)」. 「이하」·「이상」이 문자열에 들어
 * 있어 모델이 방향을 읽고, 기준값이 함께 가서 많은지 적은지 스스로 판단하지 않는다.
 */
data class RecentDaySummary(
    val date: LocalDate,
    /** 그날 기록이 없으면 null. 「기록 없음」으로 렌더링한다. */
    val dayScore: Int?,
    val totalKcal: Double,
    val warnings: List<String>,
    /** 끼니 종류 → 음식 이름들. 확정 순서 그대로다. */
    val meals: List<Pair<MealType, List<String>>>,
)
