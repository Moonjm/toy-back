package com.toy.backend.diet.food

/**
 * `scripts/build-food-csv.py`가 만든 정제본을 읽는다.
 *
 * **이름 컬럼을 마지막에 둔 이유** — 음식명에는 쉼표가 들어갈 수 있다. 이름이 마지막이면
 * `split(',', limit = COLUMN_COUNT)`의 마지막 조각이 나머지 전부라 인용부호 처리 없이 안전하다.
 * **컬럼을 더할 때는 반드시 `name` 앞에 넣는다** — 뒤에 넣으면 이름에 쉼표가 있는 행이 밀린다.
 */
object FoodCsvParser {
    private const val COLUMN_COUNT = 15

    /** CSV 안에서 `estimatedFields`를 잇는 구분자. 파일 구분자(`,`)와 겹치면 컬럼이 밀린다. */
    private const val ESTIMATED_SEPARATOR = '|'

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
        val name = columns[14].trim().takeIf { it.isNotBlank() } ?: return null
        // 브랜드는 없는 행이 더 많다(음식 68%). 빈 칸은 null로 두어 검색 조건에서 자연히 빠진다.
        val maker = columns[13].trim().takeIf { it.isNotBlank() }
        val estimatedFields = parseEstimatedFields(columns[12])
        // 탄단지 값이 없는 행은 틀린 값을 넣느니 버리고 LLM 추정에 맡긴다.
        // **기준량은 기본값을 채우기 전에 판단해야 한다** — 먼저 채우면 결측이 「200g을 아는 것」이
        // 되어 `servingSizeKnown`이 참으로 잡힌다(원재료 523행이 통째로 그렇게 됐던 자리다).
        val parsedServing = columns[1].trim().toDoubleOrNull()
        val kcal = columns[2].trim().toDoubleOrNull() ?: return null
        val carbs = columns[3].trim().toDoubleOrNull() ?: return null
        val protein = columns[4].trim().toDoubleOrNull() ?: return null
        val fat = columns[5].trim().toDoubleOrNull() ?: return null
        // 주의 영양소는 없으면 0으로 채운다 — 탄단지가 멀쩡한 행을 이것 때문에 버리면 손실이 크다.
        val sugar = columns[6].trim().toDoubleOrNull() ?: 0.0
        val sodium = columns[7].trim().toDoubleOrNull() ?: 0.0
        val fiber = columns[8].trim().toDoubleOrNull() ?: 0.0
        val saturatedFat = columns[9].trim().toDoubleOrNull() ?: 0.0
        val transFat = columns[10].trim().toDoubleOrNull() ?: 0.0
        val cholesterol = columns[11].trim().toDoubleOrNull() ?: 0.0
        val trustedServing = parsedServing?.takeIf { it > 0 && it <= FoodPolicy.MAX_TRUSTED_SERVING_SIZE_G }

        return Food(
            code = code,
            name = name,
            normalizedName = FoodNameNormalizer.normalize(name),
            maker = maker,
            normalizedMaker = maker?.let { FoodNameNormalizer.normalize(it) }?.takeIf { it.isNotBlank() },
            dataset = dataset,
            // 0 이하는 결측이고, 상한을 넘는 값은 1회 제공량이 아니라 포장 총중량이다
            // (`MAX_TRUSTED_SERVING_SIZE_G` 주석 참고). 둘 다 기본값으로 되돌리되
            // **기본값으로 채웠다는 사실을 함께 남긴다** — 안 남기면 저장된 200과 원래 200을
            // 구분할 수 없어, 근거 없는 기본값이 「DB가 아는 값」처럼 쓰인다.
            servingSizeG = trustedServing ?: FoodPolicy.DEFAULT_SERVING_SIZE_G,
            servingSizeKnown = trustedServing != null,
            kcalPer100g = kcal,
            carbsPer100g = carbs,
            proteinPer100g = protein,
            fatPer100g = fat,
            sugarPer100g = sugar,
            sodiumMgPer100g = sodium,
            fiberPer100g = fiber,
            saturatedFatPer100g = saturatedFat,
            transFatPer100g = transFat,
            cholesterolMgPer100g = cholesterol,
            estimatedFields = estimatedFields,
        )
    }

    /**
     * `carbs|fat` → `"carbs,fat"`. 빈 칸이면 null이다.
     *
     * **모르는 이름은 무시하되 행은 살린다.** 오타 하나로 멀쩡한 영양소까지 통째로 잃는 것은
     * 손해가 크다 — 이 값은 화면 배지 하나를 좌우할 뿐이다.
     */
    private fun parseEstimatedFields(raw: String): String? =
        raw
            .split(ESTIMATED_SEPARATOR)
            .map { it.trim() }
            .filter { it in FoodPolicy.ESTIMABLE_FIELDS }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(",")
}
