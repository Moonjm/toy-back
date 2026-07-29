package com.toy.backend.diet.food

import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * `scripts/build-food-csv.py`가 만든 정제본을 읽는다.
 *
 * **이름 컬럼을 마지막에 둔 이유** — 음식명에는 쉼표가 들어갈 수 있다. 이름이 마지막이면
 * `split(',', limit = 7)`의 7번째 조각이 나머지 전부라 인용부호 처리 없이 안전하다.
 */
object FoodCsvParser {
    private const val COLUMN_COUNT = 7

    fun parse(lines: Sequence<String>): List<Food> {
        var dropped = 0
        val foods =
            lines
                .drop(1) // 헤더
                .mapNotNull { line ->
                    val food = parseLine(line)
                    if (food == null && line.isNotBlank()) dropped++
                    food
                }.toList()
        if (dropped > 0) log.warn { "식품 CSV에서 파싱할 수 없는 행 ${dropped}건을 건너뛴다" }
        return foods
    }

    private fun parseLine(line: String): Food? {
        if (line.isBlank()) return null
        val columns = line.split(',', limit = COLUMN_COUNT)
        if (columns.size < COLUMN_COUNT) return null

        val code = columns[0].trim().takeIf { it.isNotBlank() } ?: return null
        val name = columns[6].trim().takeIf { it.isNotBlank() } ?: return null
        // 기준량이 비면 기본값으로 채운다. 영양소 값이 없는 행은 틀린 값을 넣느니 버리고
        // LLM 추정에 맡긴다.
        val servingSizeG = columns[1].trim().toDoubleOrNull() ?: FoodPolicy.DEFAULT_SERVING_SIZE_G
        val kcal = columns[2].trim().toDoubleOrNull() ?: return null
        val carbs = columns[3].trim().toDoubleOrNull() ?: return null
        val protein = columns[4].trim().toDoubleOrNull() ?: return null
        val fat = columns[5].trim().toDoubleOrNull() ?: return null

        return Food(
            code = code,
            name = name,
            normalizedName = FoodNameNormalizer.normalize(name),
            servingSizeG = if (servingSizeG > 0) servingSizeG else FoodPolicy.DEFAULT_SERVING_SIZE_G,
            kcalPer100g = kcal,
            carbsPer100g = carbs,
            proteinPer100g = protein,
            fatPer100g = fat,
        )
    }
}
