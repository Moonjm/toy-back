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
        val service =
            MealService(
                repository,
                userRepository,
                profileService,
                analysisService,
                analysisRepository,
                fileService,
                objectMapper,
            )

        val user = dietUser()
        val profile = dummyProfile(user = user, weightKg = 70.0)

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

                Then("임시 분석 레코드는 지운다") {
                    verify { analysisRepository.delete(analysis) }
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
        }
    })
