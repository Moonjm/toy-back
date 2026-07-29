package com.toy.backend.diet.food

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class FoodCsvParserTest :
    BehaviorSpec({
        val header =
            "code,servingSizeG,kcalPer100g,carbsPer100g,proteinPer100g,fatPer100g," +
                "sugarPer100g,sodiumMgPer100g,fiberPer100g,name"

        Given("정상 행") {
            When("한 줄을 파싱하면") {
                val foods =
                    FoodCsvParser
                        .parse(
                            sequenceOf(header, "D000001,300,180.5,12.3,15.1,8.2,3.4,620,2.1,제육볶음"),
                            FoodDataset.DISH,
                        ).toList()

                Then("주의 영양소까지 함께 담긴다") {
                    foods.size shouldBe 1
                    foods[0].code shouldBe "D000001"
                    foods[0].name shouldBe "제육볶음"
                    foods[0].normalizedName shouldBe FoodNameNormalizer.normalize("제육볶음")
                    foods[0].dataset shouldBe FoodDataset.DISH
                    foods[0].servingSizeG shouldBe 300.0
                    foods[0].kcalPer100g shouldBe 180.5
                    foods[0].sugarPer100g shouldBe 3.4
                    foods[0].sodiumMgPer100g shouldBe 620.0
                    foods[0].fiberPer100g shouldBe 2.1
                }
            }
        }

        Given("주의 영양소가 빈 행") {
            When("파싱하면") {
                val foods =
                    FoodCsvParser
                        .parse(sequenceOf(header, "D000007,200,150,20,10,3,,,,된장국"), FoodDataset.DISH)
                        .toList()

                Then("0으로 채우고 행은 살린다 — 탄단지가 멀쩡한데 버리면 그 음식을 못 쓴다") {
                    foods.size shouldBe 1
                    foods[0].sugarPer100g shouldBe 0.0
                    foods[0].sodiumMgPer100g shouldBe 0.0
                    foods[0].fiberPer100g shouldBe 0.0
                }
            }
        }

        Given("이름에 쉼표가 든 행") {
            When("파싱하면") {
                val foods =
                    FoodCsvParser
                        .parse(sequenceOf(header, "D000002,200,100,10,5,2,1,300,1,밥, 국"), FoodDataset.DISH)
                        .toList()

                Then("이름 컬럼이 마지막이라 쉼표가 그대로 살아난다") {
                    foods[0].name shouldBe "밥, 국"
                }
            }
        }

        Given("1인분 기준량이 비어 있는 행") {
            When("파싱하면") {
                val foods =
                    FoodCsvParser
                        .parse(sequenceOf(header, "D000003,,150,20,10,3,1,300,1,김치찌개"), FoodDataset.DISH)
                        .toList()

                Then("기본값 200g으로 채운다 — 없다고 버리면 매칭 자체가 안 된다") {
                    foods[0].servingSizeG shouldBe 200.0
                }
            }
        }

        Given("망가진 행") {
            When("컬럼 수가 모자라거나 숫자가 아니면") {
                val foods =
                    FoodCsvParser
                        .parse(
                            sequenceOf(
                                header,
                                "D000004,200,150",
                                "D000005,200,없음,20,10,3,1,300,1,된장찌개",
                                "",
                                "D000006,200,150,20,10,3,1,300,1,비빔밥",
                            ),
                            FoodDataset.DISH,
                        ).toList()

                Then("그 행만 버리고 나머지는 살린다") {
                    foods.size shouldBe 1
                    foods[0].code shouldBe "D000006"
                }
            }
        }
    })
