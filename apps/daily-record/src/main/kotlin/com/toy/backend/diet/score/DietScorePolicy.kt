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

    /**
     * 끼니 점수 — 범위를 1%p 벗어날 때마다 깎는 점수.
     *
     * **2.0에서 1.0으로 내렸다. 튜닝이 아니라 이중 계산을 되돌린 것이다.**
     *
     * 세 비율은 합이 100%라서 서로 독립이 아니다. 지방이 30%p 높으면 탄수화물은 필연적으로
     * 그만큼 낮아지는데, 세 감점을 그냥 더하면 **하나의 불균형을 두 번 세게 된다.**
     * 음식DB 6,090종을 한 가지만 먹은 끼니로 재 보니 범위를 벗어난 5,764건 중 89%가 두 개
     * 이상의 매크로에서 동시에 걸렸고, 감점 합이 가장 큰 감점의 평균 1.62배였다.
     *
     * 그 결과 2.0에서는 **6,090종 중 1,506종(24.7%)이 0점**이었다. 치킨 한 마리도, 삼겹살도,
     * 실제로 기록한 제육볶음+두부+잡곡밥 한 끼도 전부 0점이라 서로 구분되지 않았다.
     * 1.0에서는 0점이 71종(1.2%)으로 줄고 중앙값이 49 → 74가 된다. 여전히 0점이 나오지만
     * (탄수화물이 아예 없는 순수 지방 같은 경우) 그건 실제로 극단적인 구성이다.
     *
     * 남는 한계 — 끼니 점수는 **구성만 보고 양을 보지 않는다.** 한 항목의 양이 잘못 인식되면
     * (밥 한 공기가 50g으로 잡히는 식) 비율이 통째로 흔들린다. 그건 이 상수로 풀 문제가 아니다.
     */
    const val MEAL_PENALTY_PER_PERCENT = 1.0

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
