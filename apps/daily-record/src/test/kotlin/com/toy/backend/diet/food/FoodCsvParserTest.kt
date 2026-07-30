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

                // 기본값을 먼저 채운 뒤 신뢰 여부를 판단하면 결측이 「200g을 아는 것」이 된다.
                // 실제로 원재료 523행(1인분 컬럼이 아예 없는 데이터셋)이 통째로 그렇게 잡혔었다.
                Then("아는 값이 아니라고 표시한다 — 상한 초과와 같은 취급이다") {
                    foods[0].servingSizeKnown shouldBe false
                }
            }
        }

        // 원본의 `1인(회)분량 참고량` 컬럼이 사라져 `식품중량`으로 폴백하는데, 가공식품의
        // 식품중량은 포장 총중량이다(냉동 해쉬브라운 한 봉지 640g, 치킨볼 2kg). 그대로 두면
        // 한 조각이 640g·1069kcal로 기록된다.
        Given("1인분 기준량이 포장 총중량인 행") {
            When("상한(500g)을 넘으면") {
                val foods =
                    FoodCsvParser
                        .parse(sequenceOf(header, "D000004,640,167,23,3,7,0,236,0,해쉬브라운"), FoodDataset.PROCESSED)
                        .toList()

                Then("믿지 않고 기본값으로 되돌린다") {
                    foods[0].servingSizeG shouldBe 200.0
                }

                // 이 표시가 없으면 저장된 200과 원래 200을 구분할 수 없어, 근거 없는 기본값이
                // 「DB가 아는 값」처럼 쓰인다 — 1kg 새우칩이 200g이 된 뒤 2인분이 곱해졌다.
                Then("기본값으로 채웠다는 사실을 남긴다") {
                    foods[0].servingSizeKnown shouldBe false
                }

                Then("100g당 값은 건드리지 않는다 — 틀린 것은 기준량이지 영양소가 아니다") {
                    foods[0].kcalPer100g shouldBe 167.0
                    foods[0].sodiumMgPer100g shouldBe 236.0
                }
            }

            When("정확히 상한이면") {
                val foods =
                    FoodCsvParser
                        .parse(sequenceOf(header, "D000005,500,167,23,3,7,0,236,0,경계값"), FoodDataset.PROCESSED)
                        .toList()

                Then("그대로 쓴다 — 경계는 포함이다") {
                    foods[0].servingSizeG shouldBe 500.0
                    foods[0].servingSizeKnown shouldBe true
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
