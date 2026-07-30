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

        Given("음식명 매칭 — 데이터셋 우선순위") {
            When("음식DB에 완전일치가 있으면") {
                val dish = dummyFood(name = "김치찌개", normalizedName = "김치찌개", dataset = FoodDataset.DISH)
                every { repository.findFirstByDatasetAndNormalizedName(FoodDataset.DISH, "김치찌개") } returns dish

                val matched = matcher.match("김치 찌개")

                Then("거기서 끝낸다 — 가공식품도, 부분일치도 보지 않는다") {
                    matched shouldBe dish
                    verify(exactly = 0) { repository.findFirstByDatasetAndNormalizedName(FoodDataset.PROCESSED, any()) }
                    verify(exactly = 0) { repository.searchByDatasetAndNormalizedName(any(), any(), any()) }
                }
            }

            // 실기동에서 과일 접시의 「복숭아」가 가공식품(말린 것으로 보이는 225kcal/100g)에 걸려
            // 200g이 450kcal로 잡혔다. 생복숭아는 80kcal다. 원재료가 가공식품보다 앞서야 한다.
            When("음식DB엔 없고 원재료와 가공식품 양쪽에 완전일치가 있으면") {
                val raw = dummyFood(code = "R001", name = "복숭아", normalizedName = "복숭아", dataset = FoodDataset.RAW, id = 7L)
                every { repository.findFirstByDatasetAndNormalizedName(FoodDataset.DISH, "복숭아") } returns null
                every { repository.findFirstByDatasetAndNormalizedName(FoodDataset.RAW, "복숭아") } returns raw

                val matched = matcher.match("복숭아")

                Then("원재료를 고른다 — 이름이 같으면 말린 가공품보다 생것이 사진에 가깝다") {
                    matched shouldBe raw
                }

                Then("가공식품은 조회조차 하지 않는다") {
                    verify(exactly = 0) { repository.findFirstByDatasetAndNormalizedName(FoodDataset.PROCESSED, "복숭아") }
                }
            }

            When("음식DB엔 없고 가공식품에 완전일치가 있으면") {
                val snack = dummyFood(code = "P001", name = "새우깡", normalizedName = "새우깡", dataset = FoodDataset.PROCESSED, id = 2L)
                every { repository.findFirstByDatasetAndNormalizedName(FoodDataset.DISH, "새우깡") } returns null
                every { repository.findFirstByDatasetAndNormalizedName(FoodDataset.RAW, "새우깡") } returns null
                every { repository.findFirstByDatasetAndNormalizedName(FoodDataset.PROCESSED, "새우깡") } returns snack

                val matched = matcher.match("새우깡")

                Then("포장 사진에서 읽힌 브랜드명이 여기서 걸린다") {
                    matched shouldBe snack
                }

                Then("가공식품은 부분일치 대상이 아니다") {
                    verify(exactly = 0) { repository.searchByDatasetAndNormalizedName(FoodDataset.PROCESSED, any(), any()) }
                }
            }

            When("완전일치가 어느 쪽에도 없으면") {
                val similar = dummyFood(name = "제육볶음", normalizedName = "제육볶음", dataset = FoodDataset.DISH, id = 3L)
                every { repository.findFirstByDatasetAndNormalizedName(any(), "돼지고기제육볶음") } returns null
                every {
                    repository.searchByDatasetAndNormalizedName(FoodDataset.DISH, "돼지고기제육볶음", any<Pageable>())
                } returns listOf(similar)

                val matched = matcher.match("돼지고기 제육볶음")

                Then("음식DB만 부분일치로 훑는다 — 브랜드 30만 행을 긁으면 매칭이 망가진다") {
                    matched shouldBe similar
                }
            }

            When("어디에도 없으면") {
                every { repository.findFirstByDatasetAndNormalizedName(any(), "없는음식") } returns null
                every { repository.searchByDatasetAndNormalizedName(FoodDataset.DISH, "없는음식", any<Pageable>()) } returns emptyList()

                Then("null — 호출자가 LLM 추정값으로 fallback 한다") {
                    matcher.match("없는 음식") shouldBe null
                }
            }

            When("정규화하면 빈 문자열이 되는 이름이면") {
                Then("조회하지 않고 null") {
                    matcher.match("!!!") shouldBe null
                    verify(exactly = 0) { repository.findFirstByDatasetAndNormalizedName(any(), "") }
                }
            }
        }

        Given("사용자 검색 — GET /diet/foods") {
            When("검색어를 넣으면") {
                every { repository.searchByNormalizedName("새우깡", any<Pageable>()) } returns
                    listOf(dummyFood(code = "P001", name = "새우깡", dataset = FoodDataset.PROCESSED, id = 4L))

                val found = matcher.search("새우깡", size = 20)

                Then("두 데이터셋을 모두 뒤진다 — 사람이 목록에서 직접 고르는 화면이다") {
                    found.size shouldBe 1
                    found[0].dataset shouldBe FoodDataset.PROCESSED
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
