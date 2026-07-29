package com.toy.backend.diet.daily

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.entity.withAudit
import com.toy.backend.common.entity.withId
import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.NutritionSource
import com.toy.backend.diet.dietUser
import com.toy.backend.diet.meal.FrequentItemResponse
import com.toy.backend.diet.meal.FrequentItemService
import com.toy.backend.diet.meal.Meal
import com.toy.backend.diet.meal.MealItem
import com.toy.backend.diet.meal.MealRepository
import com.toy.backend.diet.meal.MealType
import com.toy.backend.diet.score.DietScoreCalculator
import com.toy.backend.user.UserRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import java.time.LocalDateTime

class DietStatsServiceTest :
    BehaviorSpec({
        val mealRepository = mockk<MealRepository>()
        val frequentItemService = mockk<FrequentItemService>()
        val userRepository = mockk<UserRepository>()
        val service = DietStatsService(mealRepository, frequentItemService, userRepository)

        val user = dietUser()
        val from = LocalDate.of(2026, 7, 22)
        val to = LocalDate.of(2026, 7, 28)

        /** 목표에 정확히 맞는 끼니 — 그날 점수가 100이 된다. */
        fun perfectMeal(
            id: Long,
            date: LocalDate,
            targetKcal: Int = 2000,
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
                    targetSugarG = 100,
                    targetSodiumMg = 2300,
                    targetFiberG = 30,
                ).withId(id)
            meal.replaceItems(
                listOf(
                    MealItem(
                        meal = meal,
                        foodName = "완벽한 한 끼",
                        foodCode = "D1",
                        quantityG = 500.0,
                        kcal = 2000.0,
                        carbsG = 275.0,
                        proteinG = 75.0,
                        fatG = 67.0,
                        source = NutritionSource.DB_MATCHED,
                    ).withId(id * 10),
                ),
            )
            return meal
        }

        beforeContainer {
            every { userRepository.findByUsername("testuser") } returns user
            every { frequentItemService.aggregate(user, from, to) } returns emptyList()
        }

        Given("기간 통계") {
            When("7일 중 2일만 기록했으면") {
                every { mealRepository.findByUserAndDateBetweenOrderByDateAscCreatedAtAscIdAsc(user, from, to) } returns
                    listOf(perfectMeal(1L, LocalDate.of(2026, 7, 22)), perfectMeal(2L, LocalDate.of(2026, 7, 25)))

                val stats = service.stats("testuser", from, to)

                Then("기록한 날로만 평균을 낸다 — 안 적은 날을 0으로 세면 평균이 무의미해진다") {
                    stats.recordedDays shouldBe 2
                    stats.averageDayScore shouldBe 100
                    stats.averageIntake!!.kcal shouldBe 2000.0
                }

                Then("일별 점수가 날짜순으로 온다") {
                    stats.dailyScores.size shouldBe 2
                    stats.dailyScores[0].date shouldBe LocalDate.of(2026, 7, 22)
                    stats.dailyScores[0].dayScore shouldBe 100
                }
            }

            When("기록이 하나도 없으면") {
                every { mealRepository.findByUserAndDateBetweenOrderByDateAscCreatedAtAscIdAsc(user, from, to) } returns emptyList()

                val stats = service.stats("testuser", from, to)

                Then("0건 상태를 준다 — 오류가 아니다") {
                    stats.recordedDays shouldBe 0
                    stats.averageDayScore shouldBe null
                    stats.averageIntake shouldBe null
                    stats.dailyScores shouldBe emptyList()
                }
            }

            When("from이 to보다 뒤면") {
                Then("INVALID_REQUEST") {
                    val e = shouldThrow<CustomException> { service.stats("testuser", to, from) }
                    e.errorCode shouldBe ErrorCode.INVALID_REQUEST
                }
            }

            When("aggregate가 상한보다 많은 음식 종류를 주면") {
                every { mealRepository.findByUserAndDateBetweenOrderByDateAscCreatedAtAscIdAsc(user, from, to) } returns emptyList()
                every { frequentItemService.aggregate(user, from, to) } returns
                    (1..30).map {
                        FrequentItemResponse(
                            foodName = "음식$it",
                            foodCode = "D$it",
                            quantityG = 100.0,
                            kcal = 100.0,
                            carbsG = 10.0,
                            proteinG = 10.0,
                            fatG = 10.0,
                            sugarG = 1.0,
                            sodiumMg = 100.0,
                            fiberG = 1.0,
                            source = NutritionSource.DB_MATCHED,
                            count = 1,
                            lastEatenOn = from,
                        )
                    }

                val stats = service.stats("testuser", from, to)

                Then("topFoods는 상한(20)으로 잘린다 — /diet/items/frequent와 같은 상한을 지킨다") {
                    stats.topFoods.size shouldBe 20
                }
            }

            When("같은 날 두 끼니의 id 순서와 createdAt 순서가 어긋나면") {
                val date = LocalDate.of(2026, 7, 22)
                // id는 더 크지만 createdAt은 더 이르다 — 리포지토리가 createdAt asc, id asc로
                // 준다는 계약을 목에서 재현한다. 그날 목표는 이 끼니의 스냅샷이어야 한다.
                val earlierByCreatedAt =
                    perfectMeal(id = 2L, date = date, targetKcal = 4000)
                        .withAudit(createdAt = LocalDateTime.of(2026, 7, 22, 8, 0))
                // id는 더 작지만 createdAt은 더 늦다 — 이 끼니의 목표가 쓰이면 안 된다.
                val laterByCreatedAt =
                    perfectMeal(id = 1L, date = date, targetKcal = 1000)
                        .withAudit(createdAt = LocalDateTime.of(2026, 7, 22, 12, 0))

                // 실제 쿼리(createdAt asc, id asc)라면 이 순서로 온다.
                every { mealRepository.findByUserAndDateBetweenOrderByDateAscCreatedAtAscIdAsc(user, from, to) } returns
                    listOf(earlierByCreatedAt, laterByCreatedAt)

                val stats = service.stats("testuser", from, to)

                Then("그날 점수는 createdAt이 이른 쪽의 목표 스냅샷으로 계산된다") {
                    // 두 끼니 항목을 합친 실제 섭취량(4000/550/150/134)에 대해 각 목표로 계산한
                    // 점수는 서로 달라야 이 테스트가 정렬 기준 회귀를 잡을 수 있다.
                    val expectedWithEarlierTarget =
                        DietScoreCalculator.scoreDay(4000.0, 550.0, 150.0, 134.0, earlierByCreatedAt.targets()).score
                    val expectedWithLaterTarget =
                        DietScoreCalculator.scoreDay(4000.0, 550.0, 150.0, 134.0, laterByCreatedAt.targets()).score
                    expectedWithEarlierTarget shouldBe stats.dailyScores[0].dayScore
                    (expectedWithEarlierTarget == expectedWithLaterTarget) shouldBe false
                }
            }
        }
    })
