package com.toy.backend.diet.daily

import com.toy.backend.diet.feedback.NutritionTotals
import com.toy.backend.diet.profile.NutritionTargets
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class NutrientLimitEvaluatorTest :
    BehaviorSpec({
        val targets = NutritionTargets(2000, 275, 75, 67, sugarG = 100, sodiumMg = 2300, fiberG = 30)

        fun totals(
            sugarG: Double,
            sodiumMg: Double,
            fiberG: Double,
        ) = NutritionTotals(2000.0, 275.0, 75.0, 67.0, sugarG, sodiumMg, fiberG)

        Given("주의 영양소 판정") {
            When("나트륨이 기준을 넘으면") {
                val limits = NutrientLimitEvaluator.evaluate(totals(50.0, 2850.0, 35.0), targets)
                val sodium = limits.first { it.name == "나트륨" }

                Then("WARN — 상한을 넘는 쪽이 문제다") {
                    sodium.status shouldBe NutrientStatus.WARN
                    sodium.intake shouldBe 2850.0
                    sodium.unit shouldBe "mg"
                    sodium.standardText shouldBe "2,300mg 이하"
                }
            }

            When("식이섬유가 기준에 못 미치면") {
                val limits = NutrientLimitEvaluator.evaluate(totals(50.0, 2000.0, 12.0), targets)
                val fiber = limits.first { it.name == "식이섬유" }

                Then("WARN — 이쪽은 미달이 문제다") {
                    fiber.status shouldBe NutrientStatus.WARN
                    fiber.standardText shouldBe "30g 이상"
                }
            }

            When("전부 기준 안이면") {
                val limits = NutrientLimitEvaluator.evaluate(totals(50.0, 2000.0, 35.0), targets)

                Then("셋 다 OK") {
                    limits.size shouldBe 3
                    limits.all { it.status == NutrientStatus.OK } shouldBe true
                }
            }

            When("정확히 기준값이면") {
                val limits = NutrientLimitEvaluator.evaluate(totals(100.0, 2300.0, 30.0), targets)

                Then("경계는 OK다 — 「이하」·「이상」이므로 같은 값은 넘지 않은 것이다") {
                    limits.all { it.status == NutrientStatus.OK } shouldBe true
                }
            }
        }
    })
