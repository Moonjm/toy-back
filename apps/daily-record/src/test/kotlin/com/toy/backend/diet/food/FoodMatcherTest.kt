package com.toy.backend.diet.food

import com.toy.backend.diet.dummyFood
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.data.domain.Pageable

class FoodMatcherTest :
    BehaviorSpec({
        val repository = mockk<FoodRepository>()
        val matcher = FoodMatcher(repository)

        Given("이름 정규화") {
            When("공백·괄호·특수문자가 섞인 이름이면") {
                Then("모두 제거하고 소문자로 만든다") {
                    FoodNameNormalizer.normalize("제육볶음 (급식용)") shouldBe "제육볶음급식용"
                    FoodNameNormalizer.normalize("Chicken-Breast") shouldBe "chickenbreast"
                }
            }
        }

        Given("음식명 매칭") {
            When("정규화된 이름이 완전일치하면") {
                val food = dummyFood(name = "제육볶음", normalizedName = "제육볶음")
                every { repository.findFirstByNormalizedName("제육볶음") } returns food

                val matched = matcher.match("제육 볶음")

                Then("그 항목을 쓰고 유사도 검색은 하지 않는다") {
                    matched shouldBe food
                    verify(exactly = 0) { repository.searchByNormalizedName(any(), any()) }
                }
            }

            When("완전일치가 없으면") {
                val shortest = dummyFood(code = "D001", name = "제육볶음", normalizedName = "제육볶음", id = 2L)
                every { repository.findFirstByNormalizedName("제육볶음") } returns null
                every { repository.searchByNormalizedName("제육볶음", any<Pageable>()) } returns listOf(shortest)

                val matched = matcher.match("제육볶음")

                Then("부분일치 후보 중 이름이 가장 짧은 것을 고른다") {
                    // 정렬은 쿼리(length asc)가 책임지므로 첫 건을 그대로 쓴다
                    matched shouldBe shortest
                }
            }

            When("후보가 아예 없으면") {
                every { repository.findFirstByNormalizedName("없는음식") } returns null
                every { repository.searchByNormalizedName("없는음식", any<Pageable>()) } returns emptyList()

                Then("null — 호출자가 LLM 추정값으로 fallback 한다") {
                    matcher.match("없는 음식") shouldBe null
                }
            }

            When("정규화하면 빈 문자열이 되는 이름이면") {
                Then("조회하지 않고 null") {
                    matcher.match("!!!") shouldBe null
                    verify(exactly = 0) { repository.findFirstByNormalizedName("") }
                }
            }
        }

        Given("1인분 배수로 영양소 산출") {
            When("1인분 300g · 100g당 150kcal인 음식을 0.5인분 먹으면") {
                val food =
                    dummyFood(servingSizeG = 300.0, kcalPer100g = 150.0, carbsPer100g = 20.0, proteinPer100g = 10.0, fatPer100g = 5.0)

                val amount = food.nutritionFor(portion = 0.5)

                Then("150g 기준으로 환산된다") {
                    amount.quantityG shouldBe 150.0
                    amount.kcal shouldBe 225.0
                    amount.carbsG shouldBe 30.0
                    amount.proteinG shouldBe 15.0
                    amount.fatG shouldBe 7.5
                }
            }
        }
    })
