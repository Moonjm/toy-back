package com.toy.backend.diet.daily

import com.toy.backend.common.entity.withId
import com.toy.backend.diet.dietUser
import com.toy.backend.diet.feedback.DailyDietFeedbackRepository
import com.toy.backend.user.UserRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDate

class DailyActivityServiceTest :
    BehaviorSpec({
        val repository = mockk<DailyActivityRepository>()
        val userRepository = mockk<UserRepository>()
        val dailyFeedbackRepository = mockk<DailyDietFeedbackRepository>()
        val service = DailyActivityService(repository, userRepository)

        val user = dietUser()
        val date = LocalDate.of(2026, 7, 29)

        beforeContainer {
            every { userRepository.findByUsername("testuser") } returns user
        }

        Given("활동 에너지 upsert") {
            When("그날 기록이 없으면") {
                every { repository.findByUserAndDate(user, date) } returns null
                every { repository.save(any()) } answers { (firstArg() as DailyActivity).withId(1L) }

                service.upsert("testuser", ActivityUpsertRequest(date = date, activeEnergyKcal = 420))

                Then("새로 저장한다") {
                    verify { repository.save(match { it.activeEnergyKcal == 420 && it.date == date }) }
                }
            }

            When("그날 기록이 있으면") {
                val existing = DailyActivity(user = user, date = date, activeEnergyKcal = 100).withId(2L)
                every { repository.findByUserAndDate(user, date) } returns existing

                service.upsert("testuser", ActivityUpsertRequest(date = date, activeEnergyKcal = 550))

                Then("값만 갱신하고 새로 만들지 않는다") {
                    existing.activeEnergyKcal shouldBe 550
                    verify(exactly = 0) { repository.save(any()) }
                }

                Then("하루 피드백 캐시는 지우지 않는다 — 활동 에너지는 하루 종일 계속 갱신되는 값이라 지우면 화면을 열 때마다 LLM을 재호출하게 된다") {
                    verify(exactly = 0) { dailyFeedbackRepository.deleteByUserAndDate(any(), any()) }
                }
            }
        }
    })
