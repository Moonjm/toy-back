package com.toy.backend.diet.meal

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.entity.withId
import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.AnalysisStatus
import com.toy.backend.diet.NutritionSource
import com.toy.backend.diet.analysis.MealAnalysisRepository
import com.toy.backend.diet.analysis.MealAnalysisService
import com.toy.backend.diet.dietUser
import com.toy.backend.diet.feedback.DailyDietFeedbackRepository
import com.toy.backend.diet.feedback.DietFeedbackGenerator
import com.toy.backend.diet.profile.NutritionProfileService
import com.toy.backend.file.FileService
import com.toy.backend.user.User
import com.toy.backend.user.UserRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.springframework.data.repository.findByIdOrNull
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 저장된 끼니의 종류를 고친다. 저녁을 간식으로 저장하면 되돌릴 길이 없던 것을 연다 —
 * 앱에 사진 바이트가 없어 「지우고 다시 만들기」로는 찍어 둔 사진이 사라진다.
 */
class MealTypeChangeTest :
    BehaviorSpec({
        val repository = mockk<MealRepository>()
        val userRepository = mockk<UserRepository>()
        val profileService = mockk<NutritionProfileService>()
        val analysisService = mockk<MealAnalysisService>()
        val analysisRepository = mockk<MealAnalysisRepository>()
        val fileService = mockk<FileService>()
        val objectMapper = jacksonObjectMapper()
        val feedbackGenerator = mockk<DietFeedbackGenerator>()
        val dailyFeedbackRepository = mockk<DailyDietFeedbackRepository>()
        val service =
            MealService(
                repository,
                userRepository,
                profileService,
                analysisService,
                analysisRepository,
                fileService,
                objectMapper,
                feedbackGenerator,
                dailyFeedbackRepository,
            )

        val user = dietUser()
        val date = LocalDate.of(2026, 8, 4)
        val past = LocalDateTime.of(2026, 8, 4, 8, 0)

        /** 확정돼 피드백까지 받은 끼니 하나. 밥 한 그릇, 점수 59. */
        fun savedMeal(
            id: Long,
            mealType: MealType,
            owner: User = user,
        ): Meal {
            val meal =
                Meal(
                    user = owner,
                    date = date,
                    mealType = mealType,
                    weightKg = 65.0,
                    targetKcal = 2000,
                    targetCarbsG = 300,
                    targetProteinG = 80,
                    targetFatG = 70,
                    targetSugarG = 100,
                    targetSodiumMg = 1500,
                    targetFiberG = 25,
                    status = AnalysisStatus.COMPLETED,
                    feedback = "기존 피드백",
                ).withId(id)
            meal.replaceItems(
                listOf(
                    MealItem(
                        meal = meal,
                        foodName = "밥",
                        foodCode = null,
                        quantityG = 210.0,
                        kcal = 300.0,
                        carbsG = 70.0,
                        proteinG = 5.0,
                        fatG = 1.0,
                        sugarG = 2.0,
                        sodiumMg = 300.0,
                        fiberG = 1.0,
                        source = NutritionSource.LLM_ESTIMATED,
                    ).withId(id * 10),
                ),
            )
            meal.applyScore(59)
            meal.contentUpdatedAt = past
            return meal
        }

        // **호출 기록을 Given마다 지운다.** 이 스펙은 `verify(exactly = 0)`을 여러 갈래에서
        // 거는데, 목이 스펙 전체에서 공유돼 앞 Given의 호출이 그대로 남는다 — 안 지우면
        // 「합칠 대상을 찾아보지도 않는다」가 앞 블록의 조회에 걸려 빨개진다. `answers = false`라
        // 스텁은 남는다.
        beforeContainer {
            clearMocks(repository, fileService, feedbackGenerator, dailyFeedbackRepository, answers = false)
            every { userRepository.findByUsername("testuser") } returns user
            justRun { feedbackGenerator.generateForMeal(any()) }
        }

        // 앱이 실수로 같은 값을 보내도 유료 호출이 나가면 안 된다.
        Given("지금과 같은 종류로 바꾸면") {
            val meal = savedMeal(70L, MealType.LUNCH)
            every { repository.findByIdOrNull(70L) } returns meal

            val id = service.changeType("testuser", 70L, MealTypeRequest(MealType.LUNCH))

            Then("요청한 id를 그대로 돌려준다") {
                id shouldBe 70L
            }

            Then("피드백을 다시 만들지 않는다 — 같은 값에 유료 호출을 걸지 않는다") {
                meal.status shouldBe AnalysisStatus.COMPLETED
                meal.feedback shouldBe "기존 피드백"
                verify(exactly = 0) { feedbackGenerator.generateForMeal(any()) }
            }

            Then("내용 판도 그대로다") {
                meal.contentUpdatedAt shouldBe past
            }

            Then("합칠 대상을 찾아보지도 않는다") {
                verify(exactly = 0) {
                    repository.findFirstByUserAndDateAndMealTypeOrderByCreatedAtAscIdAsc(any(), any(), any())
                }
            }
        }

        Given("그날 대상 종류의 끼니가 없으면") {
            val meal = savedMeal(71L, MealType.SNACK)
            every { repository.findByIdOrNull(71L) } returns meal
            every {
                repository.findFirstByUserAndDateAndMealTypeOrderByCreatedAtAscIdAsc(user, date, MealType.DINNER)
            } returns null

            val id = service.changeType("testuser", 71L, MealTypeRequest(MealType.DINNER))

            Then("같은 행의 종류만 바뀐다") {
                id shouldBe 71L
                meal.mealType shouldBe MealType.DINNER
            }

            Then("항목과 점수는 그대로다 — 점수는 종류를 읽지 않는다") {
                meal.items.size shouldBe 1
                meal.totalKcal shouldBe 300.0
                meal.score shouldBe 59
            }

            // 하루 프롬프트가 종류를 읽으므로 안 올리면 「간식: 밥」인 채로 남는다.
            Then("내용 판이 올라간다") {
                meal.contentUpdatedAt shouldBeGreaterThan past
            }

            // `DietFeedbackPrompts.meal`이 [이번 끼니] ${meal.mealType}을 읽는다.
            Then("끼니 피드백은 다시 만든다") {
                meal.status shouldBe AnalysisStatus.PENDING
                meal.feedback shouldBe null
                verify { feedbackGenerator.generateForMeal(71L) }
            }

            Then("아무것도 지우지 않는다") {
                verify(exactly = 0) { repository.delete(any()) }
                verify(exactly = 0) { fileService.detachFiles(any()) }
                verify(exactly = 0) { dailyFeedbackRepository.deleteByUserAndDate(any(), any()) }
            }
        }

        // 간식만 `mergesWithinDay = false`다. 오전 과자와 밤 아이스크림을 한 카드에 합치면
        // 끼니 점수가 뒤섞인다 — 합치기는 대칭이 아니다.
        Given("간식으로 바꾸면") {
            val meal = savedMeal(72L, MealType.DINNER)
            every { repository.findByIdOrNull(72L) } returns meal

            val id = service.changeType("testuser", 72L, MealTypeRequest(MealType.SNACK))

            Then("그날 간식이 이미 있어도 합치지 않는다 — 대상을 찾아보지도 않는다") {
                id shouldBe 72L
                meal.mealType shouldBe MealType.SNACK
                verify(exactly = 0) {
                    repository.findFirstByUserAndDateAndMealTypeOrderByCreatedAtAscIdAsc(any(), any(), any())
                }
                verify(exactly = 0) { repository.delete(any()) }
            }
        }

        Given("남의 끼니면") {
            val other = dietUser(username = "other", id = 2L)
            every { repository.findByIdOrNull(73L) } returns savedMeal(73L, MealType.LUNCH, owner = other)

            Then("RESOURCE_NOT_FOUND") {
                val e =
                    shouldThrow<CustomException> {
                        service.changeType("testuser", 73L, MealTypeRequest(MealType.DINNER))
                    }
                e.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
            }
        }

        Given("없는 id면") {
            every { repository.findByIdOrNull(999L) } returns null

            Then("RESOURCE_NOT_FOUND") {
                val e =
                    shouldThrow<CustomException> {
                        service.changeType("testuser", 999L, MealTypeRequest(MealType.DINNER))
                    }
                e.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
            }
        }
    })
