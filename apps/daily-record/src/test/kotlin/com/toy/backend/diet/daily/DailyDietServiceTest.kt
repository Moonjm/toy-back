package com.toy.backend.diet.daily

import com.toy.backend.common.entity.withAudit
import com.toy.backend.common.entity.withId
import com.toy.backend.diet.NutritionSource
import com.toy.backend.diet.dietUser
import com.toy.backend.diet.feedback.DailyDietFeedback
import com.toy.backend.diet.feedback.DailyDietFeedbackRepository
import com.toy.backend.diet.feedback.DietFeedbackGenerator
import com.toy.backend.diet.meal.Meal
import com.toy.backend.diet.meal.MealItem
import com.toy.backend.diet.meal.MealRepository
import com.toy.backend.diet.meal.MealType
import com.toy.backend.file.FileService
import com.toy.backend.user.UserRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.LocalDate
import java.time.LocalDateTime

class DailyDietServiceTest :
    BehaviorSpec({
        val mealRepository = mockk<MealRepository>()
        val feedbackRepository = mockk<DailyDietFeedbackRepository>()
        val activityRepository = mockk<DailyActivityRepository>()
        val userRepository = mockk<UserRepository>()
        val feedbackGenerator = mockk<DietFeedbackGenerator>()
        val fileService = mockk<FileService>()
        val service =
            DailyDietService(
                mealRepository,
                feedbackRepository,
                activityRepository,
                userRepository,
                feedbackGenerator,
                fileService,
            )

        val user = dietUser()
        val date = LocalDate.of(2026, 7, 29)

        /**
         * 확정 시점 스냅샷이 다른 두 끼니 — 하루 목표는 첫 끼니 것을 써야 한다.
         *
         * `sources`는 항목 하나당 하나다. 수량·영양소를 항목 수로 나눠 담으므로 **항목을 늘려도
         * 끼니 합계는 그대로다** — 출처만 섞인 끼니를 만들 때 다른 단언이 흔들리지 않게 하기 위해서다.
         */
        fun meal(
            id: Long,
            kcal: Double,
            targetKcal: Int,
            createdAt: LocalDateTime,
            updatedAt: LocalDateTime = createdAt,
            sources: List<NutritionSource> = listOf(NutritionSource.DB_MATCHED),
        ): Meal {
            val meal =
                Meal(
                    user = user,
                    date = date,
                    mealType = MealType.LUNCH,
                    weightKg = 70.0,
                    targetKcal = targetKcal,
                    targetCarbsG = 275,
                    targetProteinG = 75,
                    targetFatG = 67,
                    targetSugarG = (targetKcal * 0.20 / 4).toInt(),
                    targetSodiumMg = 2300,
                    targetFiberG = 30,
                ).withId(id).withAudit(createdAt = createdAt, updatedAt = updatedAt)
            meal.replaceItems(
                sources.mapIndexed { index, source ->
                    MealItem(
                        meal = meal,
                        foodName = "제육볶음",
                        foodCode = "D1",
                        quantityG = 200.0 / sources.size,
                        kcal = kcal / sources.size,
                        carbsG = 137.5 / sources.size,
                        proteinG = 37.5 / sources.size,
                        fatG = 33.5 / sources.size,
                        source = source,
                    ).withId(id * 100 + index)
                },
            )
            return meal
        }
        // 항목에 id를 넣는 이유: 응답 변환(MealItemResponse)이 requiredId를 읽는다.

        beforeContainer {
            every { userRepository.findByUsername("testuser") } returns user
            every { activityRepository.findByUserAndDate(user, date) } returns null
            every { fileService.getPresignedUrls(any()) } returns emptyMap()
            // 트랜잭션 없는 단위 테스트에서는 runAfterCommit이 즉시 실행한다 — MealServiceTest와 같은 전제.
            justRun { feedbackGenerator.generateForDay(any(), any(), any()) }
            every { feedbackGenerator.isAvailable } returns true
        }

        Given("그날 끼니가 없으면") {
            When("하루를 조회하면") {
                every { mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date) } returns emptyList()

                val response = service.getDay("testuser", date)

                Then("dayScore는 null이고 피드백도 만들지 않는다") {
                    response.dayScore shouldBe null
                    response.scoreBasis shouldBe null
                    response.feedback shouldBe null
                    verify(exactly = 0) { feedbackGenerator.generateForDay(any(), any(), any()) }
                }

                Then("추정 건수는 null이 아니라 0이다 — 앱이 분기를 만들지 않도록") {
                    response.estimatedItemCount shouldBe 0
                }
            }
        }

        Given("판정에 섞인 추정값") {
            When("식품DB에 매칭된 항목과 LLM 추정 항목이 섞여 있으면") {
                val mixed =
                    meal(
                        1L,
                        1000.0,
                        targetKcal = 2000,
                        createdAt = LocalDateTime.of(2026, 7, 29, 8, 0),
                        sources = listOf(NutritionSource.DB_MATCHED, NutritionSource.LLM_ESTIMATED),
                    )
                val matched = meal(2L, 1000.0, targetKcal = 2000, createdAt = LocalDateTime.of(2026, 7, 29, 19, 0))
                every { mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date) } returns listOf(mixed, matched)
                every { feedbackRepository.findByUserAndDate(user, date) } returns null
                every { feedbackRepository.save(any()) } answers { (firstArg() as DailyDietFeedback).withId(1L) }

                val response = service.getDay("testuser", date)

                Then("끼니를 가로질러 추정 건수를 센다 — 「기준 이하」가 오차 범위 안의 이야기임을 앱이 알아야 한다") {
                    response.estimatedItemCount shouldBe 1
                }

                Then("점수와 판정은 추정 여부와 무관하게 그대로다 — 이 값은 표시용이지 감점 요인이 아니다") {
                    response.dayScore shouldBe 100
                    response.nutrientLimits.size shouldBe 3
                }
            }
        }

        Given("하루 목표의 출처") {
            When("첫 끼니와 나중 끼니의 목표 스냅샷이 다르면") {
                val first = meal(1L, 1000.0, targetKcal = 2000, createdAt = LocalDateTime.of(2026, 7, 29, 8, 0))
                val second = meal(2L, 1000.0, targetKcal = 1500, createdAt = LocalDateTime.of(2026, 7, 29, 19, 0))
                every { mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date) } returns listOf(first, second)
                every { feedbackRepository.findByUserAndDate(user, date) } returns null
                every { feedbackRepository.save(any()) } answers { (firstArg() as DailyDietFeedback).withId(1L) }

                val response = service.getDay("testuser", date)

                Then("첫 끼니의 스냅샷 목표로 계산한다 — 몸무게를 갱신해도 과거 점수가 흔들리지 않는다") {
                    val basis = response.scoreBasis
                    basis.shouldNotBeNull()
                    basis.calorie.targetKcal shouldBe 2000
                    basis.calorie.intakeKcal shouldBe 2000.0
                    response.dayScore shouldBe 100
                }

                Then("주의 영양소 판정도 첫 끼니의 스냅샷 기준을 쓴다") {
                    val sugar = response.nutrientLimits.first { it.name == "당류" }
                    sugar.standardText shouldBe "100g 이하"
                }
            }
        }

        Given("응답에 담기는 끼니 순서") {
            When("저녁을 먼저, 아침을 나중에 확정했으면") {
                val dinner =
                    Meal(
                        user = user,
                        date = date,
                        mealType = MealType.DINNER,
                        weightKg = 70.0,
                        targetKcal = 2000,
                        targetCarbsG = 275,
                        targetProteinG = 75,
                        targetFatG = 67,
                        targetSugarG = 100,
                        targetSodiumMg = 2300,
                        targetFiberG = 30,
                    ).withId(5L).withAudit(createdAt = LocalDateTime.of(2026, 7, 29, 8, 0))
                dinner.replaceItems(
                    listOf(
                        MealItem(
                            meal = dinner,
                            foodName = "저녁 음식",
                            foodCode = null,
                            quantityG = 100.0,
                            kcal = 100.0,
                            carbsG = 10.0,
                            proteinG = 5.0,
                            fatG = 3.0,
                            source = NutritionSource.DB_MATCHED,
                        ).withId(500L),
                    ),
                )
                val breakfast =
                    Meal(
                        user = user,
                        date = date,
                        mealType = MealType.BREAKFAST,
                        weightKg = 70.0,
                        targetKcal = 2000,
                        targetCarbsG = 275,
                        targetProteinG = 75,
                        targetFatG = 67,
                        targetSugarG = 100,
                        targetSodiumMg = 2300,
                        targetFiberG = 30,
                    ).withId(6L).withAudit(createdAt = LocalDateTime.of(2026, 7, 29, 19, 0))
                breakfast.replaceItems(
                    listOf(
                        MealItem(
                            meal = breakfast,
                            foodName = "아침 음식",
                            foodCode = null,
                            quantityG = 100.0,
                            kcal = 100.0,
                            carbsG = 10.0,
                            proteinG = 5.0,
                            fatG = 3.0,
                            source = NutritionSource.DB_MATCHED,
                        ).withId(600L),
                    ),
                )
                // 리포지토리 정렬(createdAt 오름차순)은 목표 스냅샷을 뽑기 위한 것이라 그대로 둔다 —
                // 여기서는 저녁이 먼저 확정됐다는 뜻으로 저녁을 앞세워 반환한다.
                every { mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date) } returns listOf(dinner, breakfast)
                every { feedbackRepository.findByUserAndDate(user, date) } returns null
                every { feedbackRepository.save(any()) } answers { (firstArg() as DailyDietFeedback).withId(1L) }

                val response = service.getDay("testuser", date)

                Then("응답에는 끼니 순(아침→저녁)으로 담긴다 — 목표는 여전히 첫 확정 건(저녁)의 스냅샷이다") {
                    response.meals.map { it.mealType } shouldBe listOf(MealType.BREAKFAST, MealType.DINNER)
                }
            }
        }

        Given("피드백 캐시") {
            // 끼니 하나뿐이라 매크로는 목표의 절반이다 → 칼로리 100, 매크로 평균 50 → 0.4×100 + 0.6×50 = 70점
            val single = meal(3L, 2000.0, targetKcal = 2000, createdAt = LocalDateTime.of(2026, 7, 29, 8, 0))

            When("캐시가 없으면") {
                every { mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date) } returns listOf(single)
                every { feedbackRepository.findByUserAndDate(user, date) } returns null
                every { feedbackRepository.save(any()) } answers { (firstArg() as DailyDietFeedback).withId(1L) }

                val response = service.getDay("testuser", date)

                Then("LLM을 동기로 부르지 않고 feedback=null을 즉시 반환한다") {
                    response.dayScore shouldBe 70
                    response.feedback shouldBe null
                }

                Then("마커 행을 먼저 저장한 뒤 비동기 생성을 트리거한다 — 폴링이 호출을 중복시키지 않기 위한 표시다") {
                    val saved = slot<DailyDietFeedback>()
                    verify { feedbackRepository.save(capture(saved)) }
                    saved.captured.dayScore shouldBe 70
                    saved.captured.feedback shouldBe null
                    // **방금 찍은 마커 시각을 그대로 넘겨야 한다.** 생성이 끝났을 때 그 사이 새 마커가
                    // 찍혔는지 대조하는 값이라, 다른 값을 넘기면 낡은 문장이 늘 버려지거나 늘 실린다.
                    verify { feedbackGenerator.generateForDay(user.requiredId, date, saved.captured.generatedAt) }
                }

                Then("주의 영양소 판정이 응답에 실린다") {
                    response.nutrientLimits.size shouldBe 3
                }
            }

            When("캐시가 끼니 수정보다 나중에 만들어졌으면(유효)") {
                every { mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date) } returns listOf(single)
                every { feedbackRepository.findByUserAndDate(user, date) } returns
                    DailyDietFeedback(
                        user = user,
                        date = date,
                        dayScore = 70,
                        feedback = "캐시된 피드백",
                        generatedAt = LocalDateTime.of(2026, 7, 29, 9, 0),
                    ).withId(1L)

                val response = service.getDay("testuser", date)

                Then("캐시된 문장을 그대로 쓰고 트리거는 걸지 않는다") {
                    response.feedback shouldBe "캐시된 피드백"
                    verify(exactly = 0) { feedbackGenerator.generateForDay(any(), any(), any()) }
                    verify(exactly = 0) { feedbackRepository.save(any()) }
                }
            }

            When("끼니가 캐시보다 나중에 수정됐으면(무효)") {
                val edited =
                    meal(
                        4L,
                        2000.0,
                        targetKcal = 2000,
                        createdAt = LocalDateTime.of(2026, 7, 29, 8, 0),
                        updatedAt = LocalDateTime.of(2026, 7, 29, 20, 0),
                    )
                val cached =
                    DailyDietFeedback(
                        user = user,
                        date = date,
                        dayScore = 50,
                        feedback = "낡은 피드백",
                        generatedAt = LocalDateTime.of(2026, 7, 29, 9, 0),
                    ).withId(1L)
                every { mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date) } returns listOf(edited)
                every { feedbackRepository.findByUserAndDate(user, date) } returns cached

                val response = service.getDay("testuser", date)

                Then("낡은 문장 대신 feedback=null을 반환하고 마커로 갱신한 뒤 트리거한다") {
                    response.feedback shouldBe null
                    cached.feedback shouldBe null
                    cached.dayScore shouldBe 70
                    verify { feedbackGenerator.generateForDay(user.requiredId, date, cached.generatedAt) }
                }
            }

            // 마커를 남기면 `generateForDay`가 즉시 반환한 뒤에도 generatedAt이 끼니 updatedAt보다
            // 뒤라 유효한 캐시로 굳는다. 나중에 키를 넣어도 그 판정이 그대로여서 그날 피드백은
            // 영영 안 만들어지고, 하루 피드백에는 재시도 엔드포인트가 없어 빠져나갈 길도 없다.
            When("API 키가 없어 생성기가 준비되지 않았으면") {
                every { mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date) } returns listOf(single)
                every { feedbackRepository.findByUserAndDate(user, date) } returns null
                every { feedbackGenerator.isAvailable } returns false

                val response = service.getDay("testuser", date)

                Then("마커를 남기지 않는다 — 남기면 키를 넣은 뒤에도 캐시가 유효해 보인다") {
                    response.feedback shouldBe null
                    verify(exactly = 0) { feedbackRepository.save(any()) }
                    verify(exactly = 0) { feedbackGenerator.generateForDay(any(), any(), any()) }
                }

                Then("점수는 그대로 나온다 — 룰 기반이라 LLM과 무관하다") {
                    response.dayScore shouldBe 70
                }
            }

            // 가드를 캐시 적중 검사보다 앞에 두면 여기서 잡힌다.
            When("키가 없는데 유효한 캐시가 이미 있으면") {
                every { mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date) } returns listOf(single)
                every { feedbackRepository.findByUserAndDate(user, date) } returns
                    DailyDietFeedback(
                        user = user,
                        date = date,
                        dayScore = 70,
                        feedback = "예전에 만든 피드백",
                        generatedAt = LocalDateTime.of(2026, 7, 29, 9, 0),
                    ).withId(1L)
                every { feedbackGenerator.isAvailable } returns false

                val response = service.getDay("testuser", date)

                Then("이미 만들어 둔 문장은 그대로 보여준다") {
                    response.feedback shouldBe "예전에 만든 피드백"
                }
            }
        }
    })
