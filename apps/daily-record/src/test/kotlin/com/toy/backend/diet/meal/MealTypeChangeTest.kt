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
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
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
        val chatCards = mockk<DietChatCardWriter>()
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
                chatCards,
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
            justRun { chatCards.deleteMealCards(any()) }
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

        // 대상 끼니는 이미 사진 2장을 갖고 있다. `Meal.photos`가 @OrderBy("sortOrder asc")라
        // 0부터 다시 매기면 0,1,0,1이 되어 앱의 사진 순서가 뒤섞인다.
        Given("그날 대상 종류의 끼니가 이미 있으면") {
            val target = savedMeal(80L, MealType.DINNER)
            repeat(2) { index ->
                target.addPhoto(MealPhoto(meal = target, fileId = 20L + index, sortOrder = index).withId(800L + index))
            }
            val source = savedMeal(81L, MealType.SNACK)
            source.replaceItems(
                listOf(
                    MealItem(
                        meal = source,
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
                    ).withId(810L),
                ),
            )
            repeat(2) { index ->
                source.addPhoto(MealPhoto(meal = source, fileId = 31L + index, sortOrder = index).withId(811L + index))
            }
            target.contentUpdatedAt = past

            every { repository.findByIdOrNull(81L) } returns source
            every {
                repository.findFirstByUserAndDateAndMealTypeOrderByCreatedAtAscIdAsc(user, date, MealType.DINNER)
            } returns target
            justRun { repository.delete(source) }

            val id = service.changeType("testuser", 81L, MealTypeRequest(MealType.DINNER))

            // 앱은 이 id로 폴링 대상을 바꾼다. 이 갈래의 핵심 계약이다.
            Then("살아남은 대상의 id를 돌려준다 — 요청한 id가 아니다") {
                id shouldBe 80L
            }

            Then("항목이 대상으로 간다 — 기존 항목 위에 얹힌다") {
                target.items.size shouldBe 2
                target.items[0].foodName shouldBe "밥"
                target.items[1].foodName shouldBe "제육볶음"
            }

            Then("합계가 두 벌의 합이다") {
                target.totalKcal shouldBe 1000.0
                target.carbsG shouldBe 238.75
                target.proteinG shouldBe 23.0
                target.fatG shouldBe 18.0
            }

            // 이 도메인에서 조용히 0이 됐던 전력이 있는 세 필드다.
            Then("주의 영양소도 합으로 맞는다") {
                target.sugarG shouldBe 7.0
                target.sodiumMg shouldBe 920.0
                target.fiberG shouldBe 4.0
            }

            // source가 떨어지면 하루 응답의 estimatedItemCount가, foodCode가 떨어지면
            // 음식 빈도 집계가 조용히 샌다.
            Then("옮긴 항목의 필드가 보존된다") {
                target.items[1].foodCode shouldBe "D1"
                target.items[1].source shouldBe NutritionSource.DB_MATCHED
                target.items[1].quantityG shouldBe 168.75
            }

            Then("새 인스턴스로 베껴 붙인다 — 원본 컬렉션은 그대로다") {
                source.items.size shouldBe 1
                target.items[1] shouldNotBeSameInstanceAs source.items[0]
                target.items[1].meal shouldBeSameInstanceAs target
            }

            Then("사진도 가고 sortOrder가 대상의 최대값 다음부터 이어진다") {
                target.photos.map { it.sortOrder } shouldBe listOf(0, 1, 2, 3)
                target.photos.map { it.fileId } shouldBe listOf(20L, 21L, 31L, 32L)
            }

            Then("사진도 새 인스턴스로 베껴 붙인다 — 원본 컬렉션은 그대로다") {
                source.photos.size shouldBe 2
                target.photos[2] shouldNotBeSameInstanceAs source.photos[0]
                target.photos[3] shouldNotBeSameInstanceAs source.photos[1]
                target.photos[2].meal shouldBeSameInstanceAs target
            }

            // detach 하면 파일이 TEMP로 돌아가 04:00 배치에 수거되고, attachFile이 재연결을
            // 거부해 되돌릴 수도 없다. 화면에는 그날 멀쩡히 보이다가 며칠 뒤 깨진다.
            Then("파일을 detach 하지 않는다 — 소유가 옮겨 간 것이지 안 쓰이게 된 게 아니다") {
                verify(exactly = 0) { fileService.detachFiles(any()) }
            }

            Then("원본을 지운다") {
                verify(exactly = 1) { repository.delete(source) }
            }

            Then("점수를 합쳐진 매크로로 다시 계산한다 — 59에서 바뀐다") {
                target.score shouldBe 82
            }

            Then("대상의 내용 판이 올라가고 피드백을 다시 만든다") {
                target.contentUpdatedAt shouldBeGreaterThan past
                target.status shouldBe AnalysisStatus.PENDING
                target.feedback shouldBe null
                verify { feedbackGenerator.generateForMeal(80L) }
            }

            // ②③ 모두 contentUpdatedAt이 올라 무효화 조건에 그대로 걸린다.
            Then("하루 피드백 캐시는 직접 지우지 않는다") {
                verify(exactly = 0) { dailyFeedbackRepository.deleteByUserAndDate(any(), any()) }
            }

            // 병합은 원본을 지운다 — 그 카드가 남으면 없는 끼니를 가리킨다.
            Then("사라지는 원본의 카드를 지운다") {
                verify { chatCards.deleteMealCards(81L) }
            }
        }

        // auto-flush 함정(KDoc)의 재현이다 — 조회 순서를 실수로 바꾸면 대상 조회가 방금 바꾼
        // 자기 자신을 돌려줄 수 있다. 지금 코드에서는 도달하지 않지만(①이 같은 종류를 먼저
        // 걸러내 대상은 항상 다른 행이다), `changeType`의 `takeIf` 가드가 없으면 자기 항목을
        // 자기에게 복사해 두 배로 만들고 방금 고친 자신을 지운다 — 이 테스트는 그 가드를 고정한다.
        Given("합칠 대상 조회가 자기 자신을 돌려주면") {
            val meal = savedMeal(90L, MealType.SNACK)
            every { repository.findByIdOrNull(90L) } returns meal
            every {
                repository.findFirstByUserAndDateAndMealTypeOrderByCreatedAtAscIdAsc(user, date, MealType.DINNER)
            } returns meal

            val id = service.changeType("testuser", 90L, MealTypeRequest(MealType.DINNER))

            Then("자기 자신과 합치지 않는다 — 종류만 바뀌고 항목은 그대로다") {
                id shouldBe 90L
                meal.mealType shouldBe MealType.DINNER
                meal.items.size shouldBe 1
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
