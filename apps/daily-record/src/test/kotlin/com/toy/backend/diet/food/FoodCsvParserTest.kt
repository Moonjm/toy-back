package com.toy.backend.diet.food

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class FoodCsvParserTest :
    BehaviorSpec({
        val header = "code,servingSizeG,kcalPer100g,carbsPer100g,proteinPer100g,fatPer100g,name"

        Given("정상 행") {
            When("한 줄을 파싱하면") {
                val foods = FoodCsvParser.parse(sequenceOf(header, "D000001,300,180.5,12.3,15.1,8.2,제육볶음"))

                Then("Food로 변환되고 이름이 정규화된다") {
                    foods.size shouldBe 1
                    foods[0].code shouldBe "D000001"
                    foods[0].name shouldBe "제육볶음"
                    foods[0].normalizedName shouldBe "제육볶음"
                    foods[0].servingSizeG shouldBe 300.0
                    foods[0].kcalPer100g shouldBe 180.5
                }
            }
        }

        Given("이름에 쉼표가 든 행") {
            When("파싱하면") {
                val foods = FoodCsvParser.parse(sequenceOf(header, "D000002,200,100,10,5,2,밥, 국"))

                Then("이름 컬럼이 마지막이라 쉼표가 그대로 살아난다") {
                    foods[0].name shouldBe "밥, 국"
                }
            }
        }

        Given("1인분 기준량이 비어 있는 행") {
            When("파싱하면") {
                val foods = FoodCsvParser.parse(sequenceOf(header, "D000003,,150,20,10,3,김치찌개"))

                Then("기본값 200g으로 채운다 — 없다고 버리면 매칭 자체가 안 된다") {
                    foods[0].servingSizeG shouldBe 200.0
                }
            }
        }

        Given("망가진 행") {
            When("컬럼 수가 모자라거나 숫자가 아니면") {
                val foods =
                    FoodCsvParser.parse(
                        sequenceOf(
                            header,
                            "D000004,200,150",
                            "D000005,200,없음,20,10,3,된장찌개",
                            "",
                            "D000006,200,150,20,10,3,비빔밥",
                        ),
                    )

                Then("그 행만 버리고 나머지는 살린다") {
                    foods.size shouldBe 1
                    foods[0].code shouldBe "D000006"
                }
            }
        }
    })
