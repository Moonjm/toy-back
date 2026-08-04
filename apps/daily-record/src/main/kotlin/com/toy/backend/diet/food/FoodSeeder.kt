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
            // **수동 등록분은 세지 않는다**(`MANUAL_CODE_PREFIX`) — 아래에서 `DISH`로 넣는 그 행들이
            // 「음식DB가 이미 적재됐다」로 읽히면, CSV 없이 한 번 뜬 것만으로 음식DB가 영영 막힌다.
            if (repository.existsByDatasetAndCodeNotStartingWith(dataset, MANUAL_CODE_PREFIX)) {
                log.info { "이미 적재돼 있어 건너뛴다: dataset=$dataset" }
            } else {
                seed(path, dataset)
            }
        }
        // **수동 등록분은 데이터셋 검사를 타면 안 된다.** `DISH`로 넣는데 위에서 음식DB가 이미
        // `DISH`를 채우므로, 같은 규칙을 적용하면 첫 기동 이후 새 행이 영영 안 들어간다.
        // 매번 훑되 `on conflict (code) do nothing`이 중복을 막는다 — 몇 행짜리라 비용이 없다.
        MANUAL_DATASETS.forEach { (path, dataset) -> seed(path, dataset) }
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
            ps.setString(17, food.maker)
            ps.setString(18, food.normalizedMaker)
            ps.setString(19, food.estimatedFields)
            ps.setObject(20, now)
            ps.setObject(21, now)
        }
    }

    companion object {
        private val DATASETS =
            listOf(
                "food/food-nutrition.csv" to FoodDataset.DISH,
                "food/raw-food-nutrition.csv" to FoodDataset.RAW,
                "food/processed-food-nutrition.csv" to FoodDataset.PROCESSED,
            )

        /**
         * 공공데이터에 없어 손으로 넣은 식품. **다른 CSV와 달리 저장소에 커밋한다** — 원본에서
         * 재생성할 수 없어 여기서 지우면 되살릴 방법이 없다(`.gitignore`에 예외를 뒀다).
         *
         * `DISH`로 넣는 이유 — 새 `FoodDataset` 값을 만들면 iOS `Food.dataset`이 옵셔널이 아니라
         * **검색 결과 배열 전체가 디코딩에 실패한다**(`woori-haru` 2026-08-02 스펙의 함정 1).
         * 그쪽에 미지값 흡수가 들어가기 전까지는 기존 값만 쓴다.
         */
        private val MANUAL_DATASETS =
            listOf(
                "food/manual-food-nutrition.csv" to FoodDataset.DISH,
            )

        /**
         * 수동 등록분의 코드 접두. **적재 여부 검사가 이 규칙으로 수동 행을 걸러낸다**
         * (`FoodRepository.existsByDatasetAndCodeNotStartingWith`). 수동분은 `DISH`로 들어가는데
         * 검사 없이 매 기동 적재되므로, 걸러내지 않으면 그 행들이 음식DB의 적재 완료 신호로 읽힌다.
         *
         * CSV의 모든 행이 이 접두를 지키는지는 `FoodSeederTest`가 실제 파일에 대고 확인한다 —
         * 규칙이 깨지면 검사가 조용히 다시 오염된다.
         */
        const val MANUAL_CODE_PREFIX = "MANUAL-"
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
                              maker, normalized_maker, estimated_fields,
                              created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (code) do nothing
            """.trimIndent()
    }
}
