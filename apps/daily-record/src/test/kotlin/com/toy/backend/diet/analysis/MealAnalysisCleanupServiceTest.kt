package com.toy.backend.diet.analysis

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDateTime

class MealAnalysisCleanupServiceTest :
    BehaviorSpec({
        val repository = mockk<MealAnalysisRepository>()
        val service = MealAnalysisCleanupService(repository)

        Given("만료된 임시 분석 정리") {
            When("cutoff를 넘긴 레코드가 있으면") {
                val cutoff = LocalDateTime.of(2026, 7, 28, 4, 10)
                every { repository.deleteByCreatedAtBefore(cutoff) } returns 3L

                val purged = service.purgeExpired(cutoff)

                Then("삭제 건수를 돌려준다") {
                    purged shouldBe 3L
                    verify { repository.deleteByCreatedAtBefore(cutoff) }
                }
            }
        }
    })
