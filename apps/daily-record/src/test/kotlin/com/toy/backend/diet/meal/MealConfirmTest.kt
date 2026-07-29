package com.toy.backend.diet.meal

import com.toy.backend.common.entity.withId
import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.AnalysisStatus
import com.toy.backend.diet.DietErrorCode
import com.toy.backend.diet.NutritionSource
import com.toy.backend.diet.analysis.AnalysisResult
import com.toy.backend.diet.analysis.AnalyzedItem
import com.toy.backend.diet.analysis.AnalyzedPhoto
import com.toy.backend.diet.analysis.MealAnalysis
import com.toy.backend.diet.analysis.MealAnalysisRepository
import com.toy.backend.diet.analysis.MealAnalysisService
import com.toy.backend.diet.dietUser
import com.toy.backend.diet.dummyProfile
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
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.LocalDate

class MealConfirmTest :
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
        // 주의 영양소 목표는 픽스처 기본값과 다르게 둔다 — 1·2번이 "값을 옮기는 걸 빠뜨렸다"는
        // 종류의 버그였으므로, 우연히 기본값과 같아 통과하는 일이 없도록 못 박는다.
        val profile = dummyProfile(user = user, weightKg = 70.0, targetSodiumMg = 1999, targetSugarG = 111, targetFiberG = 22)

        fun completedAnalysis(vararg fileIds: Long): MealAnalysis =
            MealAnalysis(
                user = user,
                status = AnalysisStatus.COMPLETED,
                resultJson =
                    objectMapper.writeValueAsString(
                        AnalysisResult(
                            fileIds.map { fileId ->
                                AnalyzedPhoto(
                                    fileId = fileId,
                                    failed = false,
                                    items =
                                        listOf(
                                            AnalyzedItem(
                                                foodName = "인식된음식",
                                                foodCode = "X1",
                                                quantityG = 100.0,
                                                kcal = 999.0,
                                                carbsG = 99.0,
                                                proteinG = 99.0,
                                                fatG = 99.0,
                                                source = NutritionSource.DB_MATCHED,
                                            ),
                                        ),
                                )
                            },
                        ),
                    ),
            ).withId(3L)

        val userItems =
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
            )

        beforeContainer {
            every { userRepository.findByUsername("testuser") } returns user
            every { profileService.requireProfile(user) } returns profile
            justRun { feedbackGenerator.generateForMeal(any()) }
        }

        Given("끼니 확정") {
            When("사진 2장짜리 분석을 확정하면") {
                val analysis = completedAnalysis(11L, 12L)
                every { analysisService.requireOwned(user, 3L) } returns analysis
                every { fileService.attachFile(any(), "meals/") } returnsArgument 0
                every { repository.save(any()) } answers { (firstArg() as Meal).withId(50L) }
                justRun { analysisRepository.delete(analysis) }

                val id =
                    service.confirm(
                        "testuser",
                        MealConfirmRequest(
                            date = LocalDate.of(2026, 7, 29),
                            mealType = MealType.LUNCH,
                            analysisId = 3L,
                            items = userItems,
                        ),
                    )

                Then("사진 수만큼 attachFile을 부르고 MealPhoto를 만든다") {
                    id shouldBe 50L
                    verify(exactly = 1) { fileService.attachFile(11L, "meals/") }
                    verify(exactly = 1) { fileService.attachFile(12L, "meals/") }
                    verify { repository.save(match { it.photos.size == 2 && it.photos[1].sortOrder == 1 }) }
                }

                Then("인식 결과가 아니라 사용자가 고친 항목이 저장된다") {
                    verify {
                        repository.save(
                            match { meal ->
                                meal.items.size == 1 &&
                                    meal.items[0].foodName == "제육볶음" &&
                                    meal.totalKcal == 700.0
                            },
                        )
                    }
                }

                Then("점수는 동기로 계산되고 피드백 상태는 PENDING이다") {
                    verify {
                        repository.save(
                            match { it.score == 76 && it.status == AnalysisStatus.PENDING && it.feedback == null },
                        )
                    }
                }

                Then("확정 시점의 몸무게·목표가 스냅샷으로 복사된다") {
                    verify {
                        repository.save(
                            match { it.weightKg == 70.0 && it.targetKcal == 2509 && it.targetCarbsG == 345 },
                        )
                    }
                }

                Then("주의 영양소 목표도 함께 스냅샷으로 복사된다") {
                    verify {
                        repository.save(
                            match { it.targetSodiumMg == 1999 && it.targetSugarG == 111 && it.targetFiberG == 22 },
                        )
                    }
                }

                Then("임시 분석 레코드는 지운다") {
                    verify { analysisRepository.delete(analysis) }
                }

                Then("피드백 생성이 커밋 뒤에 예약된다 — 트랜잭션이 없는 단위 테스트에서는 즉시 실행된다") {
                    verify { feedbackGenerator.generateForMeal(50L) }
                }
            }

            When("인식이 아직 끝나지 않은 분석이면") {
                val pending =
                    MealAnalysis(user = user, status = AnalysisStatus.PENDING, resultJson = "{\"photos\":[]}").withId(4L)
                every { analysisService.requireOwned(user, 4L) } returns pending

                Then("ANALYSIS_NOT_CONFIRMABLE — 확인하지 않은 결과를 확정할 수 없다") {
                    val e =
                        shouldThrow<CustomException> {
                            service.confirm(
                                "testuser",
                                MealConfirmRequest(LocalDate.of(2026, 7, 29), MealType.LUNCH, 4L, userItems),
                            )
                        }
                    e.errorCode shouldBe DietErrorCode.ANALYSIS_NOT_CONFIRMABLE
                }
            }

            When("항목이 비어 있으면") {
                val analysis = completedAnalysis(13L)
                every { analysisService.requireOwned(user, 3L) } returns analysis

                Then("INVALID_REQUEST — 빈 끼니는 만들지 않는다") {
                    shouldThrow<CustomException> {
                        service.confirm(
                            "testuser",
                            MealConfirmRequest(LocalDate.of(2026, 7, 29), MealType.LUNCH, 3L, emptyList()),
                        )
                    }
                }
            }

            When("사진 없이(analysisId 없이) 확정하면") {
                every { repository.save(any()) } answers { (firstArg() as Meal).withId(51L) }

                val id =
                    service.confirm(
                        "testuser",
                        MealConfirmRequest(
                            date = LocalDate.of(2026, 7, 29),
                            mealType = MealType.SNACK,
                            analysisId = null,
                            items = userItems,
                        ),
                    )

                Then("사진 첨부와 분석 삭제를 아예 하지 않는다") {
                    id shouldBe 51L
                    verify(exactly = 0) { fileService.attachFile(any(), any()) }
                    verify(exactly = 0) { analysisRepository.delete(any()) }
                    verify(exactly = 0) { analysisService.requireOwned(any(), any()) }
                }

                Then("사진 없는 끼니가 저장되고 점수·스냅샷은 그대로 계산된다") {
                    verify {
                        repository.save(
                            match { it.photos.isEmpty() && it.score == 76 && it.targetKcal == 2509 },
                        )
                    }
                }

                Then("피드백 생성은 사진 유무와 무관하게 예약된다") {
                    verify { feedbackGenerator.generateForMeal(51L) }
                }
            }

            // @Size(max=30)은 ""를 막지 않는다. 그대로 저장하면 자주 먹는 음식 집계가
            // 빈 코드끼리 한 덩어리로 묶어 무관한 음식들을 합친다.
            When("foodCode가 빈 문자열로 들어오면") {
                every { repository.save(any()) } answers { (firstArg() as Meal).withId(52L) }

                service.confirm(
                    "testuser",
                    MealConfirmRequest(
                        date = LocalDate.of(2026, 7, 29),
                        mealType = MealType.SNACK,
                        analysisId = null,
                        items = userItems.map { it.copy(foodCode = "") },
                    ),
                )

                Then("null로 정규화해 저장한다") {
                    verify { repository.save(match { meal -> meal.items.all { it.foodCode == null } }) }
                }
            }
        }
    })
