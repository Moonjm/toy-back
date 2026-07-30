package com.toy.backend.diet.food

/**
 * `scripts/build-food-csv.py`가 만든 정제본을 읽는다.
 *
 * **이름 컬럼을 마지막에 둔 이유** — 음식명에는 쉼표가 들어갈 수 있다. 이름이 마지막이면
 * `split(',', limit = 10)`의 10번째 조각이 나머지 전부라 인용부호 처리 없이 안전하다.
 */
object FoodCsvParser {
    private const val COLUMN_COUNT = 10

    /**
     * 지연 평가로 돌려준다 — 가공식품 30만 행을 List로 만들면 라즈베리파이 힙이 감당하지 못한다.
     * 호출자가 청크 단위로 소비하는 것을 전제하며, **스트림이 열려 있는 동안 소비해야 한다.**
     */
    fun parse(
        lines: Sequence<String>,
        dataset: FoodDataset,
    ): Sequence<Food> =
        lines
            .drop(1) // 헤더
            .mapNotNull { parseLine(it, dataset) }

    private fun parseLine(
        line: String,
        dataset: FoodDataset,
    ): Food? {
        if (line.isBlank()) return null
        val columns = line.split(',', limit = COLUMN_COUNT)
        if (columns.size < COLUMN_COUNT) return null

        val code = columns[0].trim().takeIf { it.isNotBlank() } ?: return null
        val name = columns[9].trim().takeIf { it.isNotBlank() } ?: return null
        // 기준량이 비면 기본값으로 채운다. 탄단지 값이 없는 행은 틀린 값을 넣느니 버리고
        // LLM 추정에 맡긴다.
        val servingSizeG = columns[1].trim().toDoubleOrNull() ?: FoodPolicy.DEFAULT_SERVING_SIZE_G
        val kcal = columns[2].trim().toDoubleOrNull() ?: return null
        val carbs = columns[3].trim().toDoubleOrNull() ?: return null
        val protein = columns[4].trim().toDoubleOrNull() ?: return null
        val fat = columns[5].trim().toDoubleOrNull() ?: return null
        // 주의 영양소는 없으면 0으로 채운다 — 탄단지가 멀쩡한 행을 이것 때문에 버리면 손실이 크다.
        val sugar = columns[6].trim().toDoubleOrNull() ?: 0.0
        val sodium = columns[7].trim().toDoubleOrNull() ?: 0.0
        val fiber = columns[8].trim().toDoubleOrNull() ?: 0.0

        return Food(
            code = code,
            name = name,
            normalizedName = FoodNameNormalizer.normalize(name),
            dataset = dataset,
            // 0 이하는 결측이고, 상한을 넘는 값은 1회 제공량이 아니라 포장 총중량이다
            // (`MAX_TRUSTED_SERVING_SIZE_G` 주석 참고). 둘 다 기본값으로 되돌린다 —
            // 여기서 거르지 않으면 인식 경로와 앱 검색 경로 양쪽이 같은 값을 그대로 쓴다.
            servingSizeG =
                servingSizeG.takeIf { it > 0 && it <= FoodPolicy.MAX_TRUSTED_SERVING_SIZE_G }
                    ?: FoodPolicy.DEFAULT_SERVING_SIZE_G,
            kcalPer100g = kcal,
            carbsPer100g = carbs,
            proteinPer100g = protein,
            fatPer100g = fat,
            sugarPer100g = sugar,
            sodiumMgPer100g = sodium,
            fiberPer100g = fiber,
        )
    }
}
