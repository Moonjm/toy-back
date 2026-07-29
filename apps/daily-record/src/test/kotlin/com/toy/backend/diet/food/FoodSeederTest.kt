package com.toy.backend.diet.food

import io.kotest.core.spec.style.BehaviorSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.jdbc.core.JdbcTemplate

/**
 * CSV는 저장소에 없고 각자 만든다(`resources/food/README.md`). **파일이 있는지에 따라 결과가
 * 달라지면 안 되므로 실제 적재를 타지 않는 경로만 본다** — 결함이 있던 자리는 「무엇을 이미
 * 적재됐다고 볼 것인가」이지 적재 자체가 아니다.
 */
class FoodSeederTest :
    BehaviorSpec({
        val repository = mockk<FoodRepository>()
        val jdbcTemplate = mockk<JdbcTemplate>()
        val seeder = FoodSeeder(repository, jdbcTemplate)

        Given("이미 적재된 데이터셋이 있는 상태로 기동하면") {
            every { repository.existsByDataset(any()) } returns true

            seeder.run(DefaultApplicationArguments())

            Then("데이터셋마다 따로 확인한다 — 전체 행 수로 보면 음식만 적재된 상태에서 가공식품이 영영 안 들어간다") {
                verify(exactly = 1) { repository.existsByDataset(FoodDataset.DISH) }
                verify(exactly = 1) { repository.existsByDataset(FoodDataset.PROCESSED) }
            }

            Then("이미 있는 것은 다시 넣지 않는다 — 기동마다 30만 행을 다시 쓰면 안 된다") {
                verify(exactly = 0) { jdbcTemplate.batchUpdate(any<String>(), any<Collection<Food>>(), any(), any()) }
            }
        }
    })
