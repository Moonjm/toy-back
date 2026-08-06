package com.toy.backend.diet.chat

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDateTime

class DietChatCleanupServiceTest :
    BehaviorSpec({
        val repository = mockk<DietChatMessageRepository>()
        val service = DietChatCleanupService(repository)
        val cutoff = LocalDateTime.of(2026, 7, 28, 4, 20)

        Given("만료된 대화가 있으면") {
            every { repository.deleteByCreatedAtBefore(cutoff) } returns 12L

            Then("지운 건수를 돌려준다") {
                service.purgeExpired(cutoff) shouldBe 12L
            }
        }
    })
