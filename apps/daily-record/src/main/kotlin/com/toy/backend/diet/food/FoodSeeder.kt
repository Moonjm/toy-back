package com.toy.backend.diet.food

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

private val log = KotlinLogging.logger {}

/**
 * `foods`가 비어 있을 때만 CSV를 적재한다. 이름 정규화 규칙을 바꿨다면 테이블을 비워야 다시 돈다.
 * 배치로 나눠 넣는 이유는 라즈베리파이 메모리다 — 수만 건을 한 번에 flush 하면 힙이 튄다.
 */
@Component
class FoodSeeder(
    private val repository: FoodRepository,
) : ApplicationRunner {
    @Transactional
    override fun run(args: ApplicationArguments) {
        if (repository.count() > 0) return

        val resource = ClassPathResource(CSV_PATH)
        if (!resource.exists()) {
            // 데이터셋을 아직 받지 않았어도 앱은 떠야 한다 — 매칭이 전부 실패해 LLM 추정으로 넘어갈 뿐이다.
            log.warn { "식품DB CSV가 없어 적재를 건너뛴다: $CSV_PATH" }
            return
        }

        var total = 0
        resource.inputStream.bufferedReader().use { reader ->
            FoodCsvParser.parse(reader.lineSequence()).chunked(BATCH_SIZE).forEach { chunk ->
                repository.saveAll(chunk)
                repository.flush()
                total += chunk.size
            }
        }
        log.info { "식품DB 적재 완료: ${total}건" }
    }

    companion object {
        private const val CSV_PATH = "food/food-nutrition.csv"
        private const val BATCH_SIZE = 500
    }
}
