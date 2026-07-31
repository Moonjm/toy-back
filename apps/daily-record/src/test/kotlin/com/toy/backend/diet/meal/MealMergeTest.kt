package com.toy.backend.diet.meal

import com.toy.backend.common.entity.withId
import com.toy.backend.diet.AnalysisStatus
import com.toy.backend.diet.NutritionSource
import com.toy.backend.diet.analysis.AnalysisResult
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
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.LocalDate

/**
 * 같은 날 같은 끼니를 다시 확정하면 새 행을 만들지 않고 합친다.
 *
 * 사용자가 실제로 겪은 문제다 — 「아침을 먹다가 하나 더 먹어서 추가했는데 아침이 두 줄이 됐다」.
 */
class MealMergeTest :
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
        val date = LocalDate.of(2026, 7, 31)

        // 두 번째 확정 시점의 프로필. **기존 끼니의 스냅샷과 일부러 다르게 둔다** — 병합이
        // 스냅샷을 덮어쓰면 여기서 잡힌다.
        val laterProfile = dummyProfile(user = user, weightKg = 72.0, targetKcal = 2509, targetSodiumMg = 1999)

        /** 이미 확정돼 피드백까지 받은 아침. 사진 2장, 밥 한 그릇. */
        fun existingBreakfast(photoCount: Int = 2): Meal {
            val meal =
                Meal(
                    user = user,
                    date = date,
                    mealType = MealType.BREAKFAST,
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
                ).withId(70L)
            repeat(photoCount) { index ->
                meal.addPhoto(MealPhoto(meal = meal, fileId = 20L + index, sortOrder = index).withId(700L + index))
            }
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
                    ).withId(701L),
                ),
            )
            meal.applyScore(18)
            return meal
        }

        val addedItems =
            listOf(
                MealItemRequest(
                    foodName = "제육볶음",
                    foodCode = "D1",
                    quantityG = 168.75,
                    kcal = 700.0,
                    carbsG = 168.75,
                    proteinG = 18.0,
                    fatG = 17.0,
                    sugarG = 5.0,
                    sodiumMg = 620.0,
                    fiberG = 3.0,
                    source = NutritionSource.DB_MATCHED,
                ),
            )

        fun analysisWith(vararg fileIds: Long): MealAnalysis =
            MealAnalysis(
                user = user,
                status = AnalysisStatus.COMPLETED,
                resultJson =
                    objectMapper.writeValueAsString(
                        AnalysisResult(fileIds.map { AnalyzedPhoto(fileId = it, failed = false, items = emptyList()) }),
                    ),
            ).withId(9L)

        beforeContainer {
            every { userRepository.findByUsername("testuser") } returns user
            every { profileService.requireProfile(user) } returns laterProfile
            justRun { feedbackGenerator.generateForMeal(any()) }
        }

        Given("같은 날 같은 아침을 다시 확정") {
            When("사진 없이 항목만 추가하면") {
                val meal = existingBreakfast()
                every {
                    repository.findFirstByUserAndDateAndMealTypeOrderByCreatedAtAscIdAsc(user, date, MealType.BREAKFAST)
                } returns meal

                val id = service.confirm("testuser", MealConfirmRequest(date, MealType.BREAKFAST, null, addedItems))

                Then("새 행을 만들지 않고 기존 끼니의 id를 돌려준다") {
                    id shouldBe 70L
                    verify(exactly = 0) { repository.save(any()) }
                }

                Then("기존 항목이 남고 새 항목이 얹힌다 — 교체가 아니다") {
                    meal.items.size shouldBe 2
                    meal.items[0].foodName shouldBe "밥"
                    meal.items[1].foodName shouldBe "제육볶음"
                }

                Then("탄단지·열량이 두 벌의 합이다") {
                    meal.totalKcal shouldBe 1000.0
                    meal.carbsG shouldBe 238.75
                    meal.proteinG shouldBe 23.0
                    meal.fatG shouldBe 18.0
                }

                // 이 도메인에서 조용히 0이 됐던 전력이 있는 세 필드다. 병합 경로에서도 반드시 센다.
                Then("주의 영양소도 합으로 맞는다") {
                    meal.sugarG shouldBe 7.0
                    meal.sodiumMg shouldBe 920.0
                    meal.fiberG shouldBe 4.0
                }

                Then("점수를 합쳐진 매크로로 다시 계산한다 — 18에서 바뀐다") {
                    meal.score shouldBe 64
                }

                // 항목이 늘었는데 옛 피드백이 남으면 없는 구성을 설명하는 문장이 된다.
                Then("피드백은 버리고 다시 만든다") {
                    meal.status shouldBe AnalysisStatus.PENDING
                    meal.feedback shouldBe null
                    verify { feedbackGenerator.generateForMeal(70L) }
                }

                // 아침 점수의 근거가 저녁에 잰 몸무게로 바뀌면 「과거 점수는 바뀌지 않는다」가 깨진다.
                Then("확정 시점 스냅샷은 갱신하지 않는다 — 지금 프로필은 72kg/2509kcal이다") {
                    meal.weightKg shouldBe 65.0
                    meal.targetKcal shouldBe 2000
                    meal.targetSodiumMg shouldBe 1500
                }

                // `delete()`와 달리 남은 끼니의 updatedAt이 실제로 올라가므로 무효화 조건에 걸린다.
                Then("하루 피드백 캐시는 직접 지우지 않는다") {
                    verify(exactly = 0) { dailyFeedbackRepository.deleteByUserAndDate(any(), any()) }
                }
            }

            // `Meal.photos`가 @OrderBy("sortOrder asc")라 0부터 다시 매기면 0,1,0,1이 되어
            // 앱의 사진 순서가 뒤섞인다.
            When("사진이 있는 분석을 합치면") {
                val meal = existingBreakfast(photoCount = 2)
                val analysis = analysisWith(31L, 32L)
                every {
                    repository.findFirstByUserAndDateAndMealTypeOrderByCreatedAtAscIdAsc(user, date, MealType.BREAKFAST)
                } returns meal
                every { analysisService.requireOwned(user, 9L) } returns analysis
                every { fileService.attachFile(any(), "meals/") } returnsArgument 0
                justRun { analysisRepository.delete(analysis) }

                service.confirm("testuser", MealConfirmRequest(date, MealType.BREAKFAST, 9L, addedItems))

                Then("sortOrder가 기존 최대값 다음부터 이어진다") {
                    meal.photos.map { it.sortOrder } shouldBe listOf(0, 1, 2, 3)
                    meal.photos.map { it.fileId } shouldBe listOf(20L, 21L, 31L, 32L)
                }

                Then("새 사진만 attach 하고 임시 분석은 지운다") {
                    verify(exactly = 1) { fileService.attachFile(31L, "meals/") }
                    verify(exactly = 1) { fileService.attachFile(32L, "meals/") }
                    verify(exactly = 0) { fileService.attachFile(20L, any()) }
                    verify { analysisRepository.delete(analysis) }
                }
            }
        }

        // 간식은 본래 여러 번이다. 오전 과자와 밤 아이스크림을 한 카드에 합치면 점수가 뒤섞인다.
        Given("같은 날 간식을 다시 확정") {
            When("확정하면") {
                every { repository.save(any()) } answers { (firstArg() as Meal).withId(80L) }

                val id = service.confirm("testuser", MealConfirmRequest(date, MealType.SNACK, null, addedItems))

                Then("합치지 않고 새로 만든다") {
                    id shouldBe 80L
                    verify { repository.save(any()) }
                }

                Then("합칠 대상을 찾아보지도 않는다") {
                    verify(exactly = 0) {
                        repository.findFirstByUserAndDateAndMealTypeOrderByCreatedAtAscIdAsc(any(), any(), any())
                    }
                }
            }
        }

        Given("그날 그 끼니가 아직 없을 때") {
            When("확정하면") {
                every {
                    repository.findFirstByUserAndDateAndMealTypeOrderByCreatedAtAscIdAsc(any(), any(), any())
                } returns null
                every { repository.save(any()) } answers { (firstArg() as Meal).withId(81L) }

                val id = service.confirm("testuser", MealConfirmRequest(date, MealType.BREAKFAST, null, addedItems))

                Then("새로 만들고 지금 프로필을 스냅샷으로 뜬다") {
                    id shouldBe 81L
                    verify { repository.save(match { it.weightKg == 72.0 && it.targetSodiumMg == 1999 }) }
                }

                // 날짜·사용자가 다르면 안 묶이는 것은 이 조회 인자가 보장한다.
                Then("요청한 사용자·날짜·끼니 종류로만 찾는다") {
                    verify {
                        repository.findFirstByUserAndDateAndMealTypeOrderByCreatedAtAscIdAsc(
                            user,
                            date,
                            MealType.BREAKFAST,
                        )
                    }
                }
            }
        }
    })
