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
import com.toy.backend.user.UserRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.data.repository.findByIdOrNull
import java.time.LocalDate
import java.time.LocalDateTime

class DietFeedbackGeneratorTest :
    BehaviorSpec({
        val mealRepository = mockk<MealRepository>()
        val activityRepository = mockk<DailyActivityRepository>()
        val feedbackRepository = mockk<DailyDietFeedbackRepository>()
        val userRepository = mockk<UserRepository>()
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
                val generator = DietFeedbackGenerator(mealRepository, activityRepository, feedbackRepository, userRepository, client)
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
                val generator = DietFeedbackGenerator(mealRepository, activityRepository, feedbackRepository, userRepository, client)
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
                val generator = DietFeedbackGenerator(mealRepository, activityRepository, feedbackRepository, userRepository, null)
                val snack = meal(4L, MealType.SNACK, 200.0, 5.0, LocalDateTime.of(2026, 7, 29, 15, 0))
                every { mealRepository.findByIdOrNull(4L) } returns snack

                generator.generateForMeal(4L)

                Then("호출을 건너뛰고 FAILED로 둔다 — 로컬에서도 확정 자체는 성공해야 한다") {
                    snack.feedback shouldBe null
                    snack.status shouldBe AnalysisStatus.FAILED
                }
            }
        }

        Given("하루 마감 피드백 생성") {
            val breakfast = meal(1L, MealType.BREAKFAST, 400.0, 10.0, LocalDateTime.of(2026, 7, 29, 8, 0))
            val lunch = meal(2L, MealType.LUNCH, 600.0, 18.0, LocalDateTime.of(2026, 7, 29, 12, 30))

            When("호출이 성공하면") {
                val generator = DietFeedbackGenerator(mealRepository, activityRepository, feedbackRepository, userRepository, client)
                // `DailyDietService`가 생성을 시작하기 전에 먼저 써 둔 마커 행 — feedback은 아직 null이다.
                val marker =
                    DailyDietFeedback(
                        user = user,
                        date = date,
                        dayScore = 0,
                        feedback = null,
                        generatedAt = LocalDateTime.of(2026, 7, 29, 20, 0),
                    ).withId(9L)
                every { userRepository.findByIdOrNull(user.requiredId) } returns user
                every { mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date) } returns listOf(breakfast, lunch)
                every { activityRepository.findByUserAndDate(user, date) } returns null
                every { feedbackRepository.findByUserAndDate(user, date) } returns marker
                val prompt = slot<String>()
                every { client.generateText(any(), capture(prompt)) } returns
                    "오늘 잘 드셨어요. 아침에 단백질이 부족했으니 간식으로 그릭요거트를 곁들이세요."

                generator.generateForDay(user.requiredId, date)

                Then("호출자가 먼저 써 둔 마커 행에 결과를 채운다") {
                    marker.feedback shouldContain "그릭요거트"
                }

                Then("그날 끼니 전체와 하루 점수가 프롬프트에 담긴다") {
                    prompt.captured shouldContain "제육볶음"
                    prompt.captured shouldContain "하루 점수"
                }
            }

            When("호출이 성공했는데 그 사이 마커가 지워졌으면") {
                // 마커는 트리거 직전에 항상 저장된다 — 여기서 없다는 것은 끼니 삭제·활동 에너지 갱신으로
                // 캐시가 이미 무효화됐다는 뜻이다. 방금 만든 문장은 낡은 구성 기준이라 되살리면 안 된다.
                val generator = DietFeedbackGenerator(mealRepository, activityRepository, feedbackRepository, userRepository, client)
                every { userRepository.findByIdOrNull(user.requiredId) } returns user
                every { mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date) } returns listOf(breakfast, lunch)
                every { activityRepository.findByUserAndDate(user, date) } returns null
                every { feedbackRepository.findByUserAndDate(user, date) } returns null
                every { client.generateText(any(), any()) } returns "이제는 낡은 문장입니다."

                generator.generateForDay(user.requiredId, date)

                Then("새 행으로 되살리지 않는다") {
                    verify(exactly = 0) { feedbackRepository.save(any()) }
                }
            }

            When("호출이 실패하면") {
                val generator = DietFeedbackGenerator(mealRepository, activityRepository, feedbackRepository, userRepository, client)
                val marker =
                    DailyDietFeedback(
                        user = user,
                        date = date,
                        dayScore = 0,
                        feedback = null,
                        generatedAt = LocalDateTime.of(2026, 7, 29, 20, 0),
                    ).withId(11L)
                every { userRepository.findByIdOrNull(user.requiredId) } returns user
                every { mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date) } returns listOf(breakfast, lunch)
                every { activityRepository.findByUserAndDate(user, date) } returns null
                every { client.generateText(any(), any()) } returns null

                generator.generateForDay(user.requiredId, date)

                Then("마커 행을 그대로 둔다 — 자동 재시도를 넣지 않는다는 설계와 맞물린다") {
                    marker.feedback.shouldBeNull()
                    verify(exactly = 0) { feedbackRepository.findByUserAndDate(any(), any()) }
                    verify(exactly = 0) { feedbackRepository.save(any()) }
                }
            }

            When("API 키가 없어 클라이언트 빈이 없으면") {
                val generator = DietFeedbackGenerator(mealRepository, activityRepository, feedbackRepository, userRepository, null)

                generator.generateForDay(user.requiredId, date)

                Then("아무것도 조회하지 않고 즉시 끝난다") {
                    verify(exactly = 0) { userRepository.findByIdOrNull(any()) }
                }
            }

            When("마커를 쓴 뒤 끼니가 모두 삭제됐으면") {
                val generator = DietFeedbackGenerator(mealRepository, activityRepository, feedbackRepository, userRepository, client)
                every { userRepository.findByIdOrNull(user.requiredId) } returns user
                every { mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date) } returns emptyList()

                generator.generateForDay(user.requiredId, date)

                Then("LLM을 부르지 않는다 — 캐시 행은 끼니 삭제 경로에서 이미 지워졌다") {
                    verify(exactly = 0) { client.generateText(any(), any()) }
                }
            }
        }
    })
