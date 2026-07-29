package com.toy.backend.diet.feedback

import com.toy.backend.common.entity.withAudit
import com.toy.backend.common.entity.withId
import com.toy.backend.diet.AnalysisStatus
import com.toy.backend.diet.NutritionSource
import com.toy.backend.diet.daily.DailyActivity
import com.toy.backend.diet.daily.DailyActivityRepository
import com.toy.backend.diet.dietUser
import com.toy.backend.diet.llm.OpenRouterClient
import com.toy.backend.diet.meal.Meal
import com.toy.backend.diet.meal.MealItem
import com.toy.backend.diet.meal.MealRepository
import com.toy.backend.diet.meal.MealType
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.springframework.data.repository.findByIdOrNull
import java.time.LocalDate
import java.time.LocalDateTime

class DietFeedbackGeneratorTest :
    BehaviorSpec({
        val mealRepository = mockk<MealRepository>()
        val activityRepository = mockk<DailyActivityRepository>()
        val client = mockk<OpenRouterClient>()

        val user = dietUser()
        val date = LocalDate.of(2026, 7, 29)

        fun meal(
            id: Long,
            mealType: MealType,
            kcal: Double,
            proteinG: Double,
            createdAt: LocalDateTime,
        ): Meal {
            val meal =
                Meal(
                    user = user,
                    date = date,
                    mealType = mealType,
                    weightKg = 70.0,
                    targetKcal = 2509,
                    targetCarbsG = 345,
                    targetProteinG = 94,
                    targetFatG = 84,
                ).withId(id).withAudit(createdAt = createdAt)
            meal.replaceItems(
                listOf(
                    MealItem(
                        meal = meal,
                        foodName = "제육볶음",
                        foodCode = "D1",
                        quantityG = 200.0,
                        kcal = kcal,
                        carbsG = 40.0,
                        proteinG = proteinG,
                        fatG = 15.0,
                        source = NutritionSource.DB_MATCHED,
                    ),
                ),
            )
            return meal
        }

        Given("끼니 피드백 생성") {
            val lunch = meal(2L, MealType.LUNCH, 600.0, 18.0, LocalDateTime.of(2026, 7, 29, 12, 30))
            val breakfast = meal(1L, MealType.BREAKFAST, 400.0, 10.0, LocalDateTime.of(2026, 7, 29, 8, 0))

            When("호출이 성공하면") {
                val generator = DietFeedbackGenerator(mealRepository, activityRepository, client)
                every { mealRepository.findByIdOrNull(2L) } returns lunch
                every { mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date) } returns listOf(breakfast, lunch)
                every { activityRepository.findByUserAndDate(user, date) } returns
                    DailyActivity(user = user, date = date, activeEnergyKcal = 350).withId(1L)
                val prompt = slot<String>()
                every { client.generateText(any(), capture(prompt)) } returns "잘 드셨어요. 단백질이 부족합니다. 저녁에 닭가슴살을 곁들여 보세요."

                generator.generateForMeal(2L)

                Then("피드백이 채워지고 상태가 COMPLETED가 된다") {
                    lunch.feedback shouldContain "닭가슴살"
                    lunch.status shouldBe AnalysisStatus.COMPLETED
                }

                Then("그날 지금까지의 누적 섭취량이 프롬프트에 담긴다 — 한 끼만 보고 말하지 않는다") {
                    prompt.captured shouldContain "누적"
                    prompt.captured shouldContain "1000" // 400 + 600 kcal
                    prompt.captured shouldContain "28" // 10 + 18 g 단백질
                }

                Then("활동 에너지도 맥락으로 넘긴다") {
                    prompt.captured shouldContain "350"
                }
            }

            When("호출이 실패하면") {
                val generator = DietFeedbackGenerator(mealRepository, activityRepository, client)
                val dinner = meal(3L, MealType.DINNER, 700.0, 30.0, LocalDateTime.of(2026, 7, 29, 19, 0))
                every { mealRepository.findByIdOrNull(3L) } returns dinner
                every { mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date) } returns listOf(dinner)
                every { activityRepository.findByUserAndDate(user, date) } returns null
                every { client.generateText(any(), any()) } returns null

                generator.generateForMeal(3L)

                Then("점수는 살리고 상태만 FAILED — 점수가 피드백보다 중요하다") {
                    dinner.feedback shouldBe null
                    dinner.status shouldBe AnalysisStatus.FAILED
                    dinner.totalKcal shouldBe 700.0
                }
            }

            When("API 키가 없어 클라이언트 빈이 없으면") {
                val generator = DietFeedbackGenerator(mealRepository, activityRepository, null)
                val snack = meal(4L, MealType.SNACK, 200.0, 5.0, LocalDateTime.of(2026, 7, 29, 15, 0))
                every { mealRepository.findByIdOrNull(4L) } returns snack

                generator.generateForMeal(4L)

                Then("호출을 건너뛰고 FAILED로 둔다 — 로컬에서도 확정 자체는 성공해야 한다") {
                    snack.feedback shouldBe null
                    snack.status shouldBe AnalysisStatus.FAILED
                }
            }
        }
    })
