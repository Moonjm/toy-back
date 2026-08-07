package com.toy.backend.diet.chat

import com.toy.backend.common.entity.withId
import com.toy.backend.diet.dietUser
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.LocalDate

class DietChatCardWriterTest :
    BehaviorSpec({
        val repository = mockk<DietChatMessageRepository>()
        val writer = DietChatCardWriter(repository)

        val user = dietUser()
        val date = LocalDate.of(2026, 8, 1)

        Given("끼니 카드를 쓰면") {
            val saved = slot<DietChatMessage>()
            every { repository.save(capture(saved)) } answers { firstArg<DietChatMessage>().withId(1L) }

            writer.writeMealCard(user, date, mealId = 42L)

            Then("MEAL_CARD 한 행이 그 끼니를 가리킨다") {
                saved.captured.type shouldBe ChatMessageType.MEAL_CARD
                saved.captured.mealId shouldBe 42L
                saved.captured.date shouldBe date
            }

            // 코치가 놓은 것이라 화면에서 왼쪽에 선다.
            Then("역할은 ASSISTANT다") {
                saved.captured.role shouldBe ChatRole.ASSISTANT
            }

            // 내용은 참조로만 채워진다 — 스냅샷을 담으면 끼니를 고칠 때 카드가 낡는다.
            Then("본문은 비어 있다") {
                saved.captured.content shouldBe ""
            }
        }

        // 총평은 끼니를 고칠 때마다 무효화되고 다시 생성되어 이 자리가 여러 번 불린다.
        // 참조 방식이라 기존 행이 새 총평을 가리키므로 또 만들 이유가 없다.
        Given("그날 총평 카드가 이미 있으면") {
            every {
                repository.existsByUserAndDateAndType(user, date, ChatMessageType.DAY_SUMMARY)
            } returns true

            writer.writeDaySummary(user, date)

            Then("또 쌓지 않는다") {
                verify(exactly = 0) { repository.save(any()) }
            }
        }

        Given("그날 총평 카드가 없으면") {
            val saved = slot<DietChatMessage>()
            every {
                repository.existsByUserAndDateAndType(user, date, ChatMessageType.DAY_SUMMARY)
            } returns false
            every { repository.save(capture(saved)) } answers { firstArg<DietChatMessage>().withId(2L) }

            writer.writeDaySummary(user, date)

            Then("DAY_SUMMARY 한 행이 그 날짜에 놓인다") {
                saved.captured.type shouldBe ChatMessageType.DAY_SUMMARY
                saved.captured.date shouldBe date
                saved.captured.mealId shouldBe null
            }
        }

        // 조회에서 거르면 `size + 1`로 다음 장을 판별하는 셈이 틀어진다(설계 함정 3).
        Given("끼니가 사라지면") {
            every { repository.deleteByMealId(42L) } returns 1L

            writer.deleteMealCards(42L)

            Then("그 끼니의 카드도 지운다") {
                verify { repository.deleteByMealId(42L) }
            }
        }
    })
