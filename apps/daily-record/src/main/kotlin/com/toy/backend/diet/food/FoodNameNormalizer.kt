package com.toy.backend.diet.food

/**
 * 적재 시점과 조회 시점이 같은 규칙을 써야 매칭이 성립한다 — 규칙을 바꾸면
 * `food` 테이블을 비우고 다시 적재해야 한다(`FoodSeeder`는 비어 있을 때만 돈다).
 */
object FoodNameNormalizer {
    private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]")

    fun normalize(name: String): String = NON_ALPHANUMERIC.replace(name, "").lowercase()
}
