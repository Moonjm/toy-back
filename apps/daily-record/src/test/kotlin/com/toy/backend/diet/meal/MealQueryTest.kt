package com.toy.backend.diet.meal

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.entity.withId
import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.NutritionSource
import com.toy.backend.diet.analysis.MealAnalysisRepository
import com.toy.backend.diet.analysis.MealAnalysisService
import com.toy.backend.diet.dietUser
import com.toy.backend.diet.dummyMeal
import com.toy.backend.diet.dummyMealItem
import com.toy.backend.diet.feedback.DailyDietFeedbackRepository
import com.toy.backend.diet.feedback.DietFeedbackGenerator
import com.toy.backend.diet.profile.NutritionProfileService
import com.toy.backend.file.FileService
import com.toy.backend.user.UserRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.data.repository.findByIdOrNull
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.LocalDate

/**
 * `MealConfirmTest`가 확정 흐름만 다루므로, 조회(`get`·`list`) 경로는 이 파일에서 따로 검증한다 —
 * 소유권 격리·presign 일괄 호출·응답 매핑은 확정과 독립적으로 회귀할 수 있는 지점이다.
 */
class MealQueryTest :
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

        val user = dietUser(id = 1L)
        val otherUser = dietUser(username = "other", id = 2L)

        beforeContainer {
            every { userRepository.findByUsername("testuser") } returns user
        }

        Given("끼니 단건 조회") {
            When("타인 소유 끼니를 조회하면") {
                val othersMeal = dummyMeal(user = otherUser, id = 99L)
                every { repository.findByIdOrNull(99L) } returns othersMeal

                Then("RESOURCE_NOT_FOUND — 존재를 숨긴다") {
                    val e = shouldThrow<CustomException> { service.get("testuser", 99L) }
                    e.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
                }
            }

            When("본인 소유 끼니를 조회하면") {
                // toResponse()가 MealItem.requiredId를 읽으므로 id 없는 엔티티로 부르면 터진다 —
                // 그래서 항목·사진 모두 withId로 영속된 상태를 흉내낸다.
                val meal = dummyMeal(user = user, id = 10L)
                meal.replaceItems(listOf(dummyMealItem(meal = meal, id = 1L, source = NutritionSource.DB_MATCHED)))
                meal.applyScore(76)
                meal.addPhoto(MealPhoto(meal = meal, fileId = 21L, sortOrder = 0).withId(2L))
                every { repository.findByIdOrNull(10L) } returns meal
                every { fileService.getPresignedUrls(listOf(21L)) } returns mapOf(21L to "https://example.com/21")

                val response = service.get("testuser", 10L)

                Then("점수·근거가 채워진다") {
                    response.score shouldBe 76
                    val basis = response.scoreBasis.shouldNotBeNull()
                    basis.macros.size shouldBe 3
                }

                Then("항목이 그대로 매핑된다") {
                    response.items.size shouldBe 1
                    response.items[0].id shouldBe 1L
                    response.items[0].foodName shouldBe "제육볶음"
                }

                Then("사진이 presign된 url과 함께 매핑된다") {
                    response.photos.size shouldBe 1
                    response.photos[0].fileId shouldBe 21L
                    response.photos[0].url shouldBe "https://example.com/21"
                    response.photos[0].sortOrder shouldBe 0
                }
            }
        }

        Given("끼니 목록 조회") {
            When("from이 to보다 이후이면") {
                Then("INVALID_REQUEST") {
                    shouldThrow<CustomException> {
                        service.list("testuser", LocalDate.of(2026, 7, 30), LocalDate.of(2026, 7, 1))
                    }
                }
            }

            When("끼니 두 건에 사진이 각각 있으면") {
                val meal1 = dummyMeal(user = user, id = 10L, date = LocalDate.of(2026, 7, 1))
                meal1.addPhoto(MealPhoto(meal = meal1, fileId = 21L, sortOrder = 0).withId(1L))
                val meal2 = dummyMeal(user = user, id = 11L, date = LocalDate.of(2026, 7, 2))
                meal2.addPhoto(MealPhoto(meal = meal2, fileId = 22L, sortOrder = 0).withId(2L))
                meal2.addPhoto(MealPhoto(meal = meal2, fileId = 23L, sortOrder = 1).withId(3L))

                every {
                    repository.findByUserAndDateBetweenOrderByDateAscIdAsc(
                        user,
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 7, 31),
                    )
                } returns listOf(meal1, meal2)
                every { fileService.getPresignedUrls(any()) } returns emptyMap()

                val responses = service.list("testuser", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31))

                Then("getPresignedUrls를 정확히 한 번, 모든 끼니의 사진 id 합집합으로 부른다") {
                    responses.size shouldBe 2
                    verify(exactly = 1) {
                        fileService.getPresignedUrls(
                            match { ids -> ids.toList().let { it.size == 3 && it.containsAll(listOf(21L, 22L, 23L)) } },
                        )
                    }
                }
            }
        }
    })
