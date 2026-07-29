package com.toy.backend.diet.score

/**
 * 점수 기준 상수. **어느 값이 국가 기준이고 어느 값이 우리가 정한 것인지 반드시 구분해 둔다** —
 * 사용자에게 점수 근거를 보여주는 기능이 있어서, 이 구분이 흐려지면 근거를 설명할 수 없게 된다.
 */
object DietScorePolicy {
    // ── 국가 기준: 2025 한국인 영양소 섭취기준(KDRIs) 에너지적정비율 ──
    // 기준이 개정되면(5년 주기) 아래 문구와 범위를 함께 바꾼다.
    const val STANDARD_NAME = "2025 한국인 영양소 섭취기준(KDRIs) 에너지적정비율"
    val CARBS_RANGE = 50.0..65.0
    val PROTEIN_RANGE = 10.0..20.0
    val FAT_RANGE = 15.0..30.0

    // ── 자체 설정값: 공개 근거 없음. 초기 추정치이며 튜닝 대상이다 ──
    const val DAY_STANDARD_NAME = "개인 목표 대비 총량 (자체 기준)"

    /** 끼니 점수 — 범위를 1%p 벗어날 때마다 깎는 점수 */
    const val MEAL_PENALTY_PER_PERCENT = 2.0

    /** 하루 칼로리 — 목표 대비 이 구간 안이면 만점 */
    const val CALORIE_TOLERANCE_LOW = 0.9
    const val CALORIE_TOLERANCE_HIGH = 1.1

    /** 하루 점수 — 허용 구간을 벗어난 비율 1.0당 깎는 점수 */
    const val PENALTY_SLOPE = 200.0

    /** 하루 매크로 — 목표의 이 배까지는 초과를 감점하지 않는다 */
    const val MACRO_OVER_TOLERANCE = 1.1

    /** 하루 점수 가중치 — 총량보다 구성이 조금 더 중요하다는 판단일 뿐이다 */
    const val CALORIE_WEIGHT = 0.4
    const val MACRO_WEIGHT = 0.6

    // ── 물리 상수 ──
    const val KCAL_PER_G_CARBS = 4.0
    const val KCAL_PER_G_PROTEIN = 4.0
    const val KCAL_PER_G_FAT = 9.0

    const val CARBS_LABEL = "탄수화물"
    const val PROTEIN_LABEL = "단백질"
    const val FAT_LABEL = "지방"
}
