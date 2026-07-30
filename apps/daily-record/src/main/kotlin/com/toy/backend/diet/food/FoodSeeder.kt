package com.toy.backend.diet.food

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

private val log = KotlinLogging.logger {}

/**
 * CSV를 적재한다. **판단은 데이터셋별로 한다** — 전체 행 수로 보면 두 CSV 중 하나만 있는 상태로
 * 처음 기동한 뒤 나머지를 채워 넣어도 영영 적재되지 않는다. CSV는 저장소에 없고 각자 만들어야
 * 하므로(`resources/food/README.md`) 하나만 먼저 준비된 상태가 정상적인 중간 단계다.
 *
 * 이름 정규화 규칙이나 CSV 포맷을 바꿨다면 해당 데이터셋의 행을 지워야 다시 돈다
 * (`delete from food where dataset = 'DISH'`).
 *
 * **JPA가 아니라 JdbcTemplate 배치로 넣는다.** 엔티티 id가 IDENTITY라 Hibernate가 JDBC 배칭을
 * 끄기 때문에, 가공식품 30만 행을 `saveAll`로 넣으면 왕복이 30만 번이 되어 라즈베리파이에서
 * 최초 기동이 수십 분으로 늘어난다. 적재는 기동 1회뿐인 쓰기라 엔티티 편의를 포기할 값이 있다.
 */
@Component
class FoodSeeder(
    private val repository: FoodRepository,
    private val jdbcTemplate: JdbcTemplate,
) : ApplicationRunner {
    @Transactional
    override fun run(args: ApplicationArguments) {
        DATASETS.forEach { (path, dataset) ->
            // 중간에 죽어 일부만 들어간 데이터셋은 「있음」으로 본다 — 이어붙이려면 지우고 다시 돌린다.
            if (repository.existsByDataset(dataset)) {
                log.info { "이미 적재돼 있어 건너뛴다: dataset=$dataset" }
            } else {
                seed(path, dataset)
            }
        }
    }

    private fun seed(
        path: String,
        dataset: FoodDataset,
    ) {
        val resource = ClassPathResource(path)
        if (!resource.exists()) {
            // 데이터셋을 아직 받지 않았어도 앱은 떠야 한다 — 매칭이 실패해 LLM 추정으로 넘어갈 뿐이다.
            log.warn { "식품DB CSV가 없어 적재를 건너뛴다: $path" }
            return
        }

        var total = 0
        resource.inputStream.bufferedReader().use { reader ->
            FoodCsvParser.parse(reader.lineSequence(), dataset).chunked(BATCH_SIZE).forEach { chunk ->
                insertAll(chunk)
                total += chunk.size
            }
        }
        log.info { "식품DB 적재 완료: dataset=$dataset, ${total}건" }
    }

    private fun insertAll(foods: List<Food>) {
        val now = LocalDateTime.now()
        jdbcTemplate.batchUpdate(INSERT_SQL, foods, foods.size) { ps, food ->
            ps.setString(1, food.code)
            ps.setString(2, food.name)
            ps.setString(3, food.normalizedName)
            ps.setString(4, food.dataset.name)
            ps.setDouble(5, food.servingSizeG)
            ps.setBoolean(6, food.servingSizeKnown)
            ps.setDouble(7, food.kcalPer100g)
            ps.setDouble(8, food.carbsPer100g)
            ps.setDouble(9, food.proteinPer100g)
            ps.setDouble(10, food.fatPer100g)
            ps.setDouble(11, food.sugarPer100g)
            ps.setDouble(12, food.sodiumMgPer100g)
            ps.setDouble(13, food.fiberPer100g)
            ps.setDouble(14, food.saturatedFatPer100g)
            ps.setDouble(15, food.transFatPer100g)
            ps.setDouble(16, food.cholesterolMgPer100g)
            ps.setObject(17, now)
            ps.setObject(18, now)
        }
    }

    companion object {
        private val DATASETS =
            listOf(
                "food/food-nutrition.csv" to FoodDataset.DISH,
                "food/raw-food-nutrition.csv" to FoodDataset.RAW,
                "food/processed-food-nutrition.csv" to FoodDataset.PROCESSED,
            )
        private const val BATCH_SIZE = 1000

        /**
         * 컬럼명은 `Food` 엔티티의 매핑과 손으로 맞춘 것이다 — 엔티티 컬럼을 바꾸면 여기도 바꿔야 한다.
         * `on conflict` 는 같은 코드가 두 번 들어오는 사고를 조용히 흘려보낸다(코드에 unique 제약이 있다).
         */
        private val INSERT_SQL =
            """
            insert into food (code, name, normalized_name, dataset, serving_size_g,
                              serving_size_known,
                              kcal_per_100g, carbs_per_100g, protein_per_100g, fat_per_100g,
                              sugar_per_100g, sodium_mg_per_100g, fiber_per_100g,
                              saturated_fat_per_100g, trans_fat_per_100g, cholesterol_mg_per_100g,
                              created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (code) do nothing
            """.trimIndent()
    }
}
