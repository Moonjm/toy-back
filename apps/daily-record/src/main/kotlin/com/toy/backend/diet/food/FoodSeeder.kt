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
 * `foods`가 비어 있을 때만 CSV를 적재한다. 이름 정규화 규칙을 바꿨다면 테이블을 비워야 다시 돈다.
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
        if (repository.count() > 0) return

        DATASETS.forEach { (path, dataset) -> seed(path, dataset) }
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
            ps.setDouble(6, food.kcalPer100g)
            ps.setDouble(7, food.carbsPer100g)
            ps.setDouble(8, food.proteinPer100g)
            ps.setDouble(9, food.fatPer100g)
            ps.setObject(10, now)
            ps.setObject(11, now)
        }
    }

    companion object {
        private val DATASETS =
            listOf(
                "food/food-nutrition.csv" to FoodDataset.DISH,
                "food/processed-food-nutrition.csv" to FoodDataset.PROCESSED,
            )
        private const val BATCH_SIZE = 1000

        /**
         * 컬럼명은 `Food` 엔티티의 매핑과 손으로 맞춘 것이다 — 엔티티 컬럼을 바꾸면 여기도 바꿔야 한다.
         * `on conflict` 는 같은 코드가 두 번 들어오는 사고를 조용히 흘려보낸다(코드에 unique 제약이 있다).
         */
        private val INSERT_SQL =
            """
            insert into foods (code, name, normalized_name, dataset, serving_size_g,
                               kcal_per_100g, carbs_per_100g, protein_per_100g, fat_per_100g,
                               created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (code) do nothing
            """.trimIndent()
    }
}
