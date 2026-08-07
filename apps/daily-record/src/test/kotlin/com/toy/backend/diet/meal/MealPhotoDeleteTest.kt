package com.toy.backend.diet.meal

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.entity.withId
import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.AnalysisStatus
import com.toy.backend.diet.NutritionSource
import com.toy.backend.diet.analysis.MealAnalysisRepository
import com.toy.backend.diet.analysis.MealAnalysisService
import com.toy.backend.diet.chat.DietChatCardWriter
import com.toy.backend.diet.dietUser
import com.toy.backend.diet.feedback.DailyDietFeedbackRepository
import com.toy.backend.diet.feedback.DietFeedbackGenerator
import com.toy.backend.diet.profile.NutritionProfileService
import com.toy.backend.file.FileService
import com.toy.backend.user.User
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

/**
 * 사진 한 장 삭제. 잘못 찍은 사진 하나 때문에 끼니를 통째로 지우고 항목을 다시 넣게 만들지 않는다.
 */
class MealPhotoDeleteTest :
    BehaviorSpec({
        val repository = mockk<MealRepository>()
        val userRepository = mockk<UserRepository>()
        val fileService = mockk<FileService>()
        val feedbackGenerator = mockk<DietFeedbackGenerator>()
        val dailyFeedbackRepository = mockk<DailyDietFeedbackRepository>()
        val chatCards = mockk<DietChatCardWriter>()
        val service =
            MealService(
                repository,
                userRepository,
                mockk<NutritionProfileService>(),
                mockk<MealAnalysisService>(),
                mockk<MealAnalysisRepository>(),
                fileService,
                jacksonObjectMapper(),
                feedbackGenerator,
                dailyFeedbackRepository,
                chatCards,
            )

        val user = dietUser()

        /** 사진 3장(sortOrder 0,1,2)에 피드백까지 받아 둔 끼니. */
        fun mealWithPhotos(
            id: Long,
            owner: User = user,
            photoCount: Int = 3,
        ): Meal {
            val meal =
                Meal(
                    user = owner,
                    date = LocalDate.of(2026, 7, 31),
                    mealType = MealType.LUNCH,
                    weightKg = 70.0,
                    targetKcal = 2509,
                    targetCarbsG = 345,
                    targetProteinG = 94,
                    targetFatG = 84,
                    targetSugarG = 125,
                    targetSodiumMg = 2300,
                    targetFiberG = 30,
                    status = AnalysisStatus.COMPLETED,
                    feedback = "기존 피드백",
                ).withId(id)
            repeat(photoCount) { index ->
                meal.addPhoto(MealPhoto(meal = meal, fileId = 30L + index, sortOrder = index).withId(300L + index))
            }
            meal.replaceItems(
                listOf(
                    MealItem(
                        meal = meal,
                        foodName = "제육볶음",
                        foodCode = "D1",
                        quantityG = 168.75,
                        kcal = 700.0,
                        carbsG = 168.75,
                        proteinG = 18.0,
                        fatG = 17.0,
                        source = NutritionSource.DB_MATCHED,
                    ).withId(3001L),
                ),
            )
            meal.applyScore(88)
            return meal
        }

        beforeContainer {
            every { userRepository.findByUsername("testuser") } returns user
            justRun { fileService.detachFiles(any()) }
            justRun { feedbackGenerator.generateForMeal(any()) }
        }

        Given("사진 3장짜리 끼니에서 가운데 한 장을 지우면") {
            val meal = mealWithPhotos(90L)
            every { repository.findByIdOrNull(90L) } returns meal

            service.deletePhoto("testuser", 90L, 31L)

            Then("그 사진만 사라진다") {
                meal.photos.map { it.fileId } shouldBe listOf(30L, 32L)
            }

            // 「전부 detach」로 잘못 짜도 위 단언만으로는 통과한다 — 멀쩡한 사진이 정리 배치에 수거된다.
            Then("detach는 지운 fileId 하나로만 부른다") {
                verify(exactly = 1) { fileService.detachFiles(listOf(31L)) }
                verify(exactly = 0) { fileService.detachFiles(listOf(30L, 31L, 32L)) }
            }

            // 다시 매기면 병합이 사진을 이어 붙일 때 쓰는 maxOfOrNull + 1 과 값이 겹칠 여지가 생긴다.
            Then("sortOrder는 다시 매기지 않는다 — 0,2로 구멍이 남는다") {
                meal.photos.map { it.sortOrder } shouldBe listOf(0, 2)
            }

            // updateItems 를 흉내 내 markFeedbackPending() 을 넣어도 위 단언들은 전부 통과한다.
            Then("점수·상태·피드백은 그대로다 — 사진이 줄어도 먹은 것은 그대로다") {
                meal.score shouldBe 88
                meal.status shouldBe AnalysisStatus.COMPLETED
                meal.feedback shouldBe "기존 피드백"
            }

            Then("피드백을 다시 만들지 않는다 — 유료 호출이 새는 자리다") {
                verify(exactly = 0) { feedbackGenerator.generateForMeal(any()) }
            }

            Then("하루 피드백 캐시도 건드리지 않는다 — 끼니 구성이 안 바뀌었다") {
                verify(exactly = 0) { dailyFeedbackRepository.deleteByUserAndDate(any(), any()) }
            }

            Then("항목은 그대로다") {
                meal.items.size shouldBe 1
                meal.totalKcal shouldBe 700.0
            }
        }

        Given("마지막 한 장을 지우면") {
            val meal = mealWithPhotos(91L, photoCount = 1)
            every { repository.findByIdOrNull(91L) } returns meal

            service.deletePhoto("testuser", 91L, 30L)

            // 사진 없는 기록(analysisId == null)이 이미 그렇게 저장되므로 사진 0장은 정상 상태다.
            Then("성공하고 끼니는 남는다") {
                meal.photos.isEmpty() shouldBe true
                meal.items.size shouldBe 1
                verify { fileService.detachFiles(listOf(30L)) }
            }
        }

        Given("그 끼니에 없는 fileId면") {
            val meal = mealWithPhotos(92L)
            every { repository.findByIdOrNull(92L) } returns meal

            Then("RESOURCE_NOT_FOUND — 다른 끼니의 사진을 지울 수 없다") {
                val e = shouldThrow<CustomException> { service.deletePhoto("testuser", 92L, 99L) }
                e.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
                meal.photos.size shouldBe 3
                verify(exactly = 0) { fileService.detachFiles(any()) }
            }
        }

        Given("타인 끼니면") {
            val other = dietUser(username = "other", id = 2L)
            val meal = mealWithPhotos(93L, owner = other)
            every { repository.findByIdOrNull(93L) } returns meal

            Then("RESOURCE_NOT_FOUND — 존재 여부를 알려 주지 않는다") {
                val e = shouldThrow<CustomException> { service.deletePhoto("testuser", 93L, 30L) }
                e.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
                meal.photos.size shouldBe 3
                verify(exactly = 0) { fileService.detachFiles(any()) }
            }
        }
    })
