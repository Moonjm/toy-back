package com.toy.backend.diet.meal

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.entity.withId
import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.AnalysisStatus
import com.toy.backend.diet.DietErrorCode
import com.toy.backend.diet.NutritionSource
import com.toy.backend.diet.analysis.MealAnalysisRepository
import com.toy.backend.diet.analysis.MealAnalysisService
import com.toy.backend.diet.dietUser
import com.toy.backend.diet.feedback.DailyDietFeedbackRepository
import com.toy.backend.diet.feedback.DietFeedbackGenerator
import com.toy.backend.diet.profile.NutritionProfileService
import com.toy.backend.file.FileService
import com.toy.backend.user.UserRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.springframework.data.repository.findByIdOrNull
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.LocalDate

class MealServiceTest :
    BehaviorSpec({
        val repository = mockk<MealRepository>()
        val userRepository = mockk<UserRepository>()
        val profileService = mockk<NutritionProfileService>()
        val analysisService = mockk<MealAnalysisService>()
        val analysisRepository = mockk<MealAnalysisRepository>()
        val fileService = mockk<FileService>()
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
                jacksonObjectMapper(),
                feedbackGenerator,
                dailyFeedbackRepository,
            )

        val user = dietUser()

        fun savedMeal(
            id: Long,
            status: AnalysisStatus = AnalysisStatus.COMPLETED,
            owner: com.toy.backend.user.User = user,
        ): Meal {
            val meal =
                Meal(
                    user = owner,
                    date = LocalDate.of(2026, 7, 29),
                    mealType = MealType.LUNCH,
                    weightKg = 70.0,
                    targetKcal = 2509,
                    targetCarbsG = 345,
                    targetProteinG = 94,
                    targetFatG = 84,
                    status = status,
                    feedback = if (status == AnalysisStatus.COMPLETED) "기존 피드백" else null,
                ).withId(id)
            meal.addPhoto(MealPhoto(meal = meal, fileId = 21L, sortOrder = 0).withId(id * 10))
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
                        source = NutritionSource.LLM_ESTIMATED,
                    ),
                ),
            )
            meal.applyScore(30)
            return meal
        }

        beforeContainer {
            every { userRepository.findByUsername("testuser") } returns user
            justRun { feedbackGenerator.generateForMeal(any()) }
        }

        Given("항목 전체 교체") {
            When("균형 잡힌 항목으로 바꾸면") {
                val meal = savedMeal(60L)
                every { repository.findByIdOrNull(60L) } returns meal

                service.updateItems(
                    "testuser",
                    60L,
                    MealItemsRequest(
                        items =
                            listOf(
                                MealItemRequest(
                                    foodName = "제육볶음",
                                    foodCode = "D1",
                                    quantityG = 168.75,
                                    kcal = 700.0,
                                    carbsG = 168.75,
                                    proteinG = 18.0,
                                    fatG = 17.0,
                                    source = NutritionSource.DB_MATCHED,
                                ),
                            ),
                    ),
                )

                Then("영양소와 점수가 다시 계산된다") {
                    meal.items.size shouldBe 1
                    meal.items[0].foodName shouldBe "제육볶음"
                    meal.totalKcal shouldBe 700.0
                    meal.score shouldBe 76
                }

                Then("피드백은 낡았으므로 비우고 재생성한다") {
                    meal.feedback shouldBe null
                    meal.status shouldBe AnalysisStatus.PENDING
                    verify { feedbackGenerator.generateForMeal(60L) }
                }
            }

            When("타인 끼니면") {
                val other = dietUser(username = "other", id = 2L)
                every { repository.findByIdOrNull(61L) } returns savedMeal(61L, owner = other)

                Then("RESOURCE_NOT_FOUND") {
                    val e =
                        shouldThrow<CustomException> {
                            service.updateItems(
                                "testuser",
                                61L,
                                MealItemsRequest(
                                    listOf(
                                        MealItemRequest("밥", null, 100.0, 100.0, 20.0, 3.0, 1.0, NutritionSource.LLM_ESTIMATED),
                                    ),
                                ),
                            )
                        }
                    e.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
                }
            }
        }

        Given("끼니 삭제") {
            When("본인 끼니를 지우면") {
                val meal = savedMeal(62L)
                every { repository.findByIdOrNull(62L) } returns meal
                justRun { fileService.detachFiles(listOf(21L)) }
                justRun { repository.delete(meal) }
                every { dailyFeedbackRepository.deleteByUserAndDate(user, meal.date) } returns 1L

                service.delete("testuser", 62L)

                Then("사진은 물리 삭제하지 않고 detach 한다 — 롤백되면 파일도 함께 살아난다") {
                    verify { fileService.detachFiles(listOf(21L)) }
                    verify { repository.delete(meal) }
                }

                Then("하루 피드백 캐시도 지운다 — 남은 끼니의 updatedAt은 안 바뀌어 무효화 조건만으로는 안 잡힌다") {
                    verify { dailyFeedbackRepository.deleteByUserAndDate(user, meal.date) }
                }
            }
        }

        Given("피드백 재생성") {
            When("FAILED 상태면") {
                val meal = savedMeal(63L, status = AnalysisStatus.FAILED)
                every { repository.findByIdOrNull(63L) } returns meal

                service.retryFeedback("testuser", 63L)

                Then("PENDING으로 되돌리고 다시 생성한다") {
                    meal.status shouldBe AnalysisStatus.PENDING
                    verify { feedbackGenerator.generateForMeal(63L) }
                }
            }

            When("이미 COMPLETED면") {
                val meal = savedMeal(64L, status = AnalysisStatus.COMPLETED)
                every { repository.findByIdOrNull(64L) } returns meal

                Then("FEEDBACK_NOT_RETRYABLE — 비용이 나가는 호출을 중복으로 하지 않는다") {
                    val e = shouldThrow<CustomException> { service.retryFeedback("testuser", 64L) }
                    e.errorCode shouldBe DietErrorCode.FEEDBACK_NOT_RETRYABLE
                }
            }
        }
    })
