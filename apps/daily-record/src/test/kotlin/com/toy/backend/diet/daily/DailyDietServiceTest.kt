package com.toy.backend.diet.daily

import com.toy.backend.common.entity.withAudit
import com.toy.backend.common.entity.withId
import com.toy.backend.diet.NutritionSource
import com.toy.backend.diet.dietUser
import com.toy.backend.diet.feedback.DailyDietFeedback
import com.toy.backend.diet.feedback.DailyDietFeedbackRepository
import com.toy.backend.diet.feedback.DietFeedbackGenerator
import com.toy.backend.diet.meal.Meal
import com.toy.backend.diet.meal.MealItem
import com.toy.backend.diet.meal.MealRepository
import com.toy.backend.diet.meal.MealType
import com.toy.backend.file.FileService
import com.toy.backend.user.UserRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDate
import java.time.LocalDateTime

class DailyDietServiceTest :
    BehaviorSpec({
        val mealRepository = mockk<MealRepository>()
        val feedbackRepository = mockk<DailyDietFeedbackRepository>()
        val activityRepository = mockk<DailyActivityRepository>()
        val userRepository = mockk<UserRepository>()
        val feedbackGenerator = mockk<DietFeedbackGenerator>()
        val fileService = mockk<FileService>()
        val service =
            DailyDietService(
                mealRepository,
                feedbackRepository,
                activityRepository,
                userRepository,
                feedbackGenerator,
                fileService,
            )

        val user = dietUser()
        val date = LocalDate.of(2026, 7, 29)

        /** 확정 시점 스냅샷이 다른 두 끼니 — 하루 목표는 첫 끼니 것을 써야 한다. */
        fun meal(
            id: Long,
            kcal: Double,
            targetKcal: Int,
            createdAt: LocalDateTime,
            updatedAt: LocalDateTime = createdAt,
        ): Meal {
            val meal =
                Meal(
                    user = user,
                    date = date,
                    mealType = MealType.LUNCH,
                    weightKg = 70.0,
                    targetKcal = targetKcal,
                    targetCarbsG = 275,
                    targetProteinG = 75,
                    targetFatG = 67,
                ).withId(id).withAudit(createdAt = createdAt, updatedAt = updatedAt)
            meal.replaceItems(
                listOf(
                    MealItem(
                        meal = meal,
                        foodName = "제육볶음",
                        foodCode = "D1",
                        quantityG = 200.0,
                        kcal = kcal,
                        carbsG = 137.5,
                        proteinG = 37.5,
                        fatG = 33.5,
                        source = NutritionSource.DB_MATCHED,
                    ).withId(id * 100),
                ),
            )
            return meal
        }
        // 항목에 id를 넣는 이유: 응답 변환(MealItemResponse)이 requiredId를 읽는다.

        beforeContainer {
            every { userRepository.findByUsername("testuser") } returns user
            every { activityRepository.findByUserAndDate(user, date) } returns null
            every { fileService.getPresignedUrls(any()) } returns emptyMap()
        }

        Given("그날 끼니가 없으면") {
            When("하루를 조회하면") {
                every { mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date) } returns emptyList()

                val response = service.getDay("testuser", date)

                Then("dayScore는 null이고 피드백도 만들지 않는다") {
                    response.dayScore shouldBe null
                    response.scoreBasis shouldBe null
                    response.feedback shouldBe null
                    verify(exactly = 0) { feedbackGenerator.generateForDay(any(), any(), any(), any(), any()) }
                }
            }
        }

        Given("하루 목표의 출처") {
            When("첫 끼니와 나중 끼니의 목표 스냅샷이 다르면") {
                val first = meal(1L, 1000.0, targetKcal = 2000, createdAt = LocalDateTime.of(2026, 7, 29, 8, 0))
                val second = meal(2L, 1000.0, targetKcal = 1500, createdAt = LocalDateTime.of(2026, 7, 29, 19, 0))
                every { mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date) } returns listOf(first, second)
                every { feedbackRepository.findByUserAndDate(user, date) } returns null
                every { feedbackGenerator.generateForDay(any(), any(), any(), any(), any()) } returns "오늘 잘 드셨어요."
                every { feedbackRepository.save(any()) } answers { (firstArg() as DailyDietFeedback).withId(1L) }

                val response = service.getDay("testuser", date)

                Then("첫 끼니의 스냅샷 목표로 계산한다 — 몸무게를 갱신해도 과거 점수가 흔들리지 않는다") {
                    response.scoreBasis!!.calorie.targetKcal shouldBe 2000
                    response.scoreBasis!!.calorie.intakeKcal shouldBe 2000.0
                    response.dayScore shouldBe 100
                }
            }
        }

        Given("피드백 캐시") {
            // 끼니 하나뿐이라 매크로는 목표의 절반이다 → 칼로리 100, 매크로 평균 50 → 0.4×100 + 0.6×50 = 70점
            val single = meal(3L, 2000.0, targetKcal = 2000, createdAt = LocalDateTime.of(2026, 7, 29, 8, 0))

            When("캐시가 끼니 수정보다 나중에 만들어졌으면") {
                every { mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date) } returns listOf(single)
                every { feedbackRepository.findByUserAndDate(user, date) } returns
                    DailyDietFeedback(
                        user = user,
                        date = date,
                        dayScore = 70,
                        feedback = "캐시된 피드백",
                        generatedAt = LocalDateTime.of(2026, 7, 29, 9, 0),
                    ).withId(1L)

                val response = service.getDay("testuser", date)

                Then("LLM을 다시 부르지 않는다") {
                    response.feedback shouldBe "캐시된 피드백"
                    verify(exactly = 0) { feedbackGenerator.generateForDay(any(), any(), any(), any(), any()) }
                }
            }

            When("끼니가 캐시보다 나중에 수정됐으면") {
                val edited =
                    meal(
                        4L,
                        2000.0,
                        targetKcal = 2000,
                        createdAt = LocalDateTime.of(2026, 7, 29, 8, 0),
                        updatedAt = LocalDateTime.of(2026, 7, 29, 20, 0),
                    )
                val cached =
                    DailyDietFeedback(
                        user = user,
                        date = date,
                        dayScore = 50,
                        feedback = "낡은 피드백",
                        generatedAt = LocalDateTime.of(2026, 7, 29, 9, 0),
                    ).withId(1L)
                every { mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date) } returns listOf(edited)
                every { feedbackRepository.findByUserAndDate(user, date) } returns cached
                every { feedbackGenerator.generateForDay(any(), any(), any(), any(), any()) } returns "새 피드백"

                val response = service.getDay("testuser", date)

                Then("버리고 다시 만든다 — 미완성 데이터로 만든 피드백이 고정되면 안 된다") {
                    response.feedback shouldBe "새 피드백"
                    cached.feedback shouldBe "새 피드백"
                    cached.dayScore shouldBe 70
                }
            }

            When("피드백 생성이 실패하면") {
                every { mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date) } returns listOf(single)
                every { feedbackRepository.findByUserAndDate(user, date) } returns null
                every { feedbackGenerator.generateForDay(any(), any(), any(), any(), any()) } returns null

                val response = service.getDay("testuser", date)

                Then("dayScore만 반환하고 실패를 캐시하지 않는다 — 다음 조회에서 다시 시도한다") {
                    response.dayScore shouldBe 70
                    response.feedback shouldBe null
                    verify(exactly = 0) { feedbackRepository.save(any()) }
                }
            }
        }
    })
