package com.toy.backend.diet.profile

/**
 * 주의 영양소 기준. **전부 2025 한국인 영양소 섭취기준(KDRIs)에서 온 국가 기준이고, 우리가 정한
 * 값이 하나도 없다** — 점수 정책(`DietScorePolicy`)과 달리 자체 추정치가 섞이지 않았다.
 * 그래서 점수에 넣지 않고 「기준 대비 표시」로만 쓴다.
 *
 * 기준이 개정되면(KDRIs는 5년 주기다) 이 상수와 `NutrientLimit`의 문구를 함께 바꾼다.
 */
object NutrientLimitPolicy {
    /** 만성질환위험감소섭취량(성인). 충분섭취량 1,500mg보다 느슨한 쪽을 상한으로 쓴다. */
    const val SODIUM_MG_LIMIT = 2300

    /** 충분섭취량 19~64세 */
    const val FIBER_G_MALE = 30
    const val FIBER_G_FEMALE = 20

    /** 에너지적정비율 상한. **총당류** 기준이며 첨가당(10% 미만)과 다르다 — 식품DB의 당류 컬럼도 총당류다. */
    const val SUGAR_ENERGY_RATIO = 0.20
    const val KCAL_PER_G_SUGAR = 4.0
}
