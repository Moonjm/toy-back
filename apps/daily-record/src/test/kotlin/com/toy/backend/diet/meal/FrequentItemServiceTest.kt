package com.toy.backend.diet.meal

import com.toy.backend.common.entity.withId
import com.toy.backend.diet.NutritionSource
import com.toy.backend.diet.dietUser
import com.toy.backend.user.UserRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class FrequentItemServiceTest :
    BehaviorSpec({
        val repository = mockk<MealItemRepository>()
        val userRepository = mockk<UserRepository>()
        val service = FrequentItemService(repository, userRepository)

        val user = dietUser()

        /** 리포지토리는 최근순으로 준다(쿼리가 그렇게 정렬한다). 대표 항목은 그 첫 건이다. */
        fun item(
            id: Long,
            name: String,
            date: LocalDate,
            quantityG: Double = 200.0,
            kcal: Double = 300.0,
            foodCode: String? = null,
            sugarG: Double = 4.0,
            sodiumMg: Double = 500.0,
            fiberG: Double = 3.0,
        ): MealItem {
            val meal =
                Meal(
                    user = user,
                    date = date,
                    mealType = MealType.LUNCH,
                    weightKg = 70.0,
                    targetKcal = 2509,
                    targetCarbsG = 345,
                    targetProteinG = 94,
                    targetFatG = 84,
                    targetSugarG = 125,
                    targetSodiumMg = 2300,
                    targetFiberG = 30,
                ).withId(id * 10)
            return MealItem(
                meal = meal,
                foodName = name,
                foodCode = foodCode,
                quantityG = quantityG,
                kcal = kcal,
                carbsG = 10.0,
                proteinG = 20.0,
                fatG = 5.0,
                sugarG = sugarG,
                sodiumMg = sodiumMg,
                fiberG = fiberG,
                source = NutritionSource.DB_MATCHED,
            ).withId(id)
        }

        beforeContainer {
            every { userRepository.findByUsername("testuser") } returns user
        }

        Given("자주 먹는 음식 목록") {
            When("제육볶음 3회, 김치찌개 1회를 먹었으면") {
                every { repository.findEatenBetween(user, any(), any()) } returns
                    listOf(
                        item(
                            1L,
                            "제육볶음",
                            LocalDate.of(2026, 7, 28),
                            quantityG = 150.0,
                            kcal = 250.0,
                            foodCode = "D1",
                            sugarG = 6.0,
                            sodiumMg = 650.0,
                            fiberG = 2.5,
                        ),
                        item(2L, "김치찌개", LocalDate.of(2026, 7, 27), foodCode = "D2"),
                        item(3L, "제육볶음", LocalDate.of(2026, 7, 20), quantityG = 300.0, kcal = 500.0, foodCode = "D1"),
                        item(4L, "제육볶음", LocalDate.of(2026, 7, 10), quantityG = 200.0, kcal = 400.0, foodCode = "D1"),
                    )

                val result = service.list("testuser", days = 30, size = 20)

                Then("빈도순으로 온다") {
                    result[0].foodName shouldBe "제육볶음"
                    result[0].count shouldBe 3
                    result[1].foodName shouldBe "김치찌개"
                    result[1].count shouldBe 1
                }

                Then("대표 수치는 가장 최근에 먹은 값이다 — 마지막에 먹은 양이 다음에 먹을 양에 가깝다") {
                    result[0].quantityG shouldBe 150.0
                    result[0].kcal shouldBe 250.0
                    result[0].lastEatenOn shouldBe LocalDate.of(2026, 7, 28)
                }

                Then("저장된 주의 영양소 값도 그대로 응답에 실린다") {
                    result[0].sugarG shouldBe 6.0
                    result[0].sodiumMg shouldBe 650.0
                    result[0].fiberG shouldBe 2.5
                }
            }

            When("빈도가 같으면") {
                every { repository.findEatenBetween(user, any(), any()) } returns
                    listOf(
                        item(1L, "비빔밥", LocalDate.of(2026, 7, 28), foodCode = "D3"),
                        item(2L, "국밥", LocalDate.of(2026, 7, 20), foodCode = "D4"),
                    )

                val result = service.list("testuser", days = 30, size = 20)

                Then("최근에 먹은 것이 위로 온다") {
                    result[0].foodName shouldBe "비빔밥"
                    result[1].foodName shouldBe "국밥"
                }
            }

            When("직접 입력해서 foodCode가 없는 항목이면") {
                every { repository.findEatenBetween(user, any(), any()) } returns
                    listOf(
                        item(1L, "엄마 김치", LocalDate.of(2026, 7, 28)),
                        item(2L, "엄마김치", LocalDate.of(2026, 7, 27)),
                    )

                val result = service.list("testuser", days = 30, size = 20)

                Then("정규화한 이름으로 묶여 띄어쓰기 차이를 흡수한다") {
                    result.size shouldBe 1
                    result[0].count shouldBe 2
                    result[0].foodName shouldBe "엄마 김치"
                }
            }

            When("size보다 종류가 많으면") {
                every { repository.findEatenBetween(user, any(), any()) } returns
                    (1L..5L).map { item(it, "음식$it", LocalDate.of(2026, 7, 20), foodCode = "D$it") }

                Then("size만큼만 자른다") {
                    service.list("testuser", days = 30, size = 2).size shouldBe 2
                }
            }

            When("기록이 없으면") {
                every { repository.findEatenBetween(user, any(), any()) } returns emptyList()

                Then("빈 목록 — 오류가 아니다") {
                    service.list("testuser", days = 30, size = 20) shouldBe emptyList()
                }
            }
        }

        Given("조회 기간") {
            // 쿼리가 between이라 양 끝이 포함된다. days만큼 그대로 빼면 days+1일이 잡힌다.
            val from = slot<LocalDate>()
            val to = slot<LocalDate>()

            When("days = 1이면") {
                every { repository.findEatenBetween(user, capture(from), capture(to)) } returns emptyList()

                service.list("testuser", days = 1, size = 20)

                Then("오늘 하루만 본다 — from과 to가 같다") {
                    from.captured shouldBe to.captured
                    to.captured shouldBe LocalDate.now()
                }
            }

            When("days = 30이면") {
                every { repository.findEatenBetween(user, capture(from), capture(to)) } returns emptyList()

                service.list("testuser", days = 30, size = 20)

                Then("30일이다 — 29일 전부터 오늘까지") {
                    from.captured shouldBe LocalDate.now().minusDays(29)
                    ChronoUnit.DAYS.between(from.captured, to.captured) + 1 shouldBe 30
                }
            }

            When("days가 상한을 넘으면") {
                every { repository.findEatenBetween(user, capture(from), capture(to)) } returns emptyList()

                service.list("testuser", days = 9999, size = 20)

                Then("90일로 자른다 — 클램프 뒤에 경계 보정이 온다") {
                    ChronoUnit.DAYS.between(from.captured, to.captured) + 1 shouldBe 90
                }
            }
        }
    })
