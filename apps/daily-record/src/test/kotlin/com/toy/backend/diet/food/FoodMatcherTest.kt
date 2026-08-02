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
                every { repository.findBestByDatasetAndNormalizedName(FoodDataset.DISH, "김치찌개", any<Pageable>()) } returns listOf(dish)

                val matched = matcher.match("김치 찌개")

                Then("거기서 끝낸다 — 가공식품도, 부분일치도 보지 않는다") {
                    matched shouldBe dish
                    verify(exactly = 0) { repository.findBestByDatasetAndNormalizedName(FoodDataset.PROCESSED, any(), any()) }
                    verify(exactly = 0) { repository.searchByDatasetAndNormalizedName(any(), any(), any()) }
                }
            }

            // 실기동에서 과일 접시의 「복숭아」가 가공식품(말린 것으로 보이는 225kcal/100g)에 걸려
            // 200g이 450kcal로 잡혔다. 생복숭아는 80kcal다. 원재료가 가공식품보다 앞서야 한다.
            When("음식DB엔 없고 원재료와 가공식품 양쪽에 완전일치가 있으면") {
                val raw = dummyFood(code = "R001", name = "복숭아", normalizedName = "복숭아", dataset = FoodDataset.RAW, id = 7L)
                every { repository.findBestByDatasetAndNormalizedName(FoodDataset.DISH, "복숭아", any<Pageable>()) } returns emptyList()
                every { repository.findBestByDatasetAndNormalizedName(FoodDataset.RAW, "복숭아", any<Pageable>()) } returns listOf(raw)

                val matched = matcher.match("복숭아")

                Then("원재료를 고른다 — 이름이 같으면 말린 가공품보다 생것이 사진에 가깝다") {
                    matched shouldBe raw
                }

                Then("가공식품은 조회조차 하지 않는다") {
                    verify(exactly = 0) { repository.findBestByDatasetAndNormalizedName(FoodDataset.PROCESSED, "복숭아", any()) }
                }
            }

            When("음식DB엔 없고 가공식품에 완전일치가 있으면") {
                val snack = dummyFood(code = "P001", name = "새우깡", normalizedName = "새우깡", dataset = FoodDataset.PROCESSED, id = 2L)
                every { repository.findBestByDatasetAndNormalizedName(FoodDataset.DISH, "새우깡", any<Pageable>()) } returns emptyList()
                every { repository.findBestByDatasetAndNormalizedName(FoodDataset.RAW, "새우깡", any<Pageable>()) } returns emptyList()
                every { repository.findBestByDatasetAndNormalizedName(FoodDataset.PROCESSED, "새우깡", any<Pageable>()) } returns listOf(snack)

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
                every { repository.findBestByDatasetAndNormalizedName(any(), "돼지고기제육볶음", any<Pageable>()) } returns emptyList()
                every {
                    repository.searchByDatasetAndNormalizedName(FoodDataset.DISH, "돼지고기제육볶음", any<Pageable>())
                } returns listOf(similar)

                val matched = matcher.match("돼지고기 제육볶음")

                Then("음식DB만 부분일치로 훑는다 — 브랜드 30만 행을 긁으면 매칭이 망가진다") {
                    matched shouldBe similar
                }
            }

            When("어디에도 없으면") {
                every { repository.findBestByDatasetAndNormalizedName(any(), "없는음식", any<Pageable>()) } returns emptyList()
                every { repository.searchByDatasetAndNormalizedName(FoodDataset.DISH, "없는음식", any<Pageable>()) } returns emptyList()

                Then("null — 호출자가 LLM 추정값으로 fallback 한다") {
                    matcher.match("없는 음식") shouldBe null
                }
            }

            When("정규화하면 빈 문자열이 되는 이름이면") {
                Then("조회하지 않고 null") {
                    matcher.match("!!!") shouldBe null
                    verify(exactly = 0) { repository.findBestByDatasetAndNormalizedName(any(), "", any()) }
                }
            }

            // 같은 정규화 이름이 여러 건인 것은 예외가 아니라 정상이다 — DISH 6,090행 중
            // 1,084종 3,452행이 그렇다(상추겉절이 8건). 리포지토리가 단건을 돌려주게 만들면
            // `IncorrectResultSizeDataAccessException`으로 끼니 확정이 통째로 죽는다.
            When("같은 이름의 완전일치가 여러 건이면") {
                val original = dummyFood(code = "D1", name = "닭튀김", normalizedName = "닭튀김", id = 11L)
                val estimated =
                    dummyFood(code = "D2", name = "닭튀김", normalizedName = "닭튀김", id = 12L)
                        .also { it.estimatedFields = "carbs,fat" }
                every {
                    repository.findBestByDatasetAndNormalizedName(FoodDataset.DISH, "닭튀김", any<Pageable>())
                } returns listOf(original, estimated)

                Then("예외 없이 첫 건을 고른다 — 리포지토리가 추정 없는 행을 앞세운다") {
                    matcher.match("닭튀김") shouldBe original
                }

                Then("한 건만 요청한다 — 중복이 많은 이름에서 목록을 통째로 끌어오면 안 된다") {
                    matcher.match("닭튀김")
                    verify {
                        repository.findBestByDatasetAndNormalizedName(
                            FoodDataset.DISH,
                            "닭튀김",
                            match<Pageable> { it.pageSize == 1 },
                        )
                    }
                }
            }
        }

        Given("사용자 검색 — GET /diet/foods") {
            When("검색어만 넣으면") {
                every { repository.searchByText("새우깡", any<Pageable>()) } returns
                    listOf(dummyFood(code = "P001", name = "새우깡", dataset = FoodDataset.PROCESSED, id = 4L))

                val found = matcher.search("새우깡", size = 20)

                Then("세 데이터셋을 모두 뒤진다 — 사람이 목록에서 직접 고르는 화면이다") {
                    found.size shouldBe 1
                    found[0].dataset shouldBe FoodDataset.PROCESSED
                }

                Then("데이터셋별 조회는 하지 않는다") {
                    verify(exactly = 0) { repository.searchByDatasetAndText(any(), any(), any()) }
                }

                // 인식 경로와 검색 경로는 규칙이 다르다 — 검색만 브랜드를 함께 본다.
                Then("인식용 부분일치 쿼리는 쓰지 않는다") {
                    verify(exactly = 0) { repository.searchByDatasetAndNormalizedName(any(), any(), any()) }
                }
            }

            // 식품명에 브랜드가 없다(`피자_뉴욕 오리진 피자 오리지널 (L)`). 이름만 보면
            // 도미노피자 318건 중 한 건도 안 나온다.
            When("브랜드 이름으로 검색하면") {
                val pizza =
                    dummyFood(code = "D900", name = "피자_뉴욕 오리진 피자 (L)", maker = "도미노피자", id = 9L)
                every { repository.searchByText("도미노", any<Pageable>()) } returns listOf(pizza)

                val found = matcher.search("도미노", size = 20)

                Then("이름이 아니라 브랜드로 걸린다") {
                    found shouldBe listOf(pizza)
                    found[0].maker shouldBe "도미노피자"
                }
            }

            // 앱에서 걸러 봐야 소용없다 — 상위 N건이 전부 가공식품이면 「음식」 칩이 빈 목록이 된다.
            // 실제로 매칭되는 조리 음식이 뒤에 있는데도 그렇다. 페이징 전에 걸러야 한다.
            When("데이터셋 칩을 함께 넣으면") {
                val dish = dummyFood(code = "D001", name = "새우볶음밥", dataset = FoodDataset.DISH, id = 5L)
                every {
                    repository.searchByDatasetAndText(FoodDataset.DISH, "새우", any<Pageable>())
                } returns listOf(dish)

                val found = matcher.search("새우", size = 20, dataset = FoodDataset.DISH)

                Then("그 데이터셋만 뒤진다") {
                    found shouldBe listOf(dish)
                    verify(exactly = 0) { repository.searchByText(any(), any()) }
                }
            }

            When("정규화하면 빈 문자열이 되는 검색어면") {
                Then("데이터셋을 줬어도 조회하지 않는다") {
                    matcher.search("!!!", size = 20, dataset = FoodDataset.RAW) shouldBe emptyList()
                    verify(exactly = 0) { repository.searchByDatasetAndText(FoodDataset.RAW, any(), any()) }
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
