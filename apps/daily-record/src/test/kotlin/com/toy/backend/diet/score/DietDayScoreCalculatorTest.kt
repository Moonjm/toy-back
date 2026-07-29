package com.toy.backend.diet.score

import com.toy.backend.diet.profile.NutritionTargets
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class DietDayScoreCalculatorTest :
    BehaviorSpec({
        val targets =
            NutritionTargets(kcal = 2000, carbsG = 275, proteinG = 75, fatG = 67, sugarG = 100, sodiumMg = 2300, fiberG = 30)

        Given("하루 점수") {
            When("칼로리·매크로가 목표와 같으면") {
                val result =
                    DietScoreCalculator.scoreDay(
                        intakeKcal = 2000.0,
                        carbsG = 275.0,
                        proteinG = 75.0,
                        fatG = 67.0,
                        targets = targets,
                    )

                Then("100점") {
                    result.score shouldBe 100
                    result.basis.calorie.calorieScore shouldBe 100
                }
            }

            When("칼로리가 목표의 90%면") {
                val result = DietScoreCalculator.scoreDay(1800.0, 275.0, 75.0, 67.0, targets)

                Then("허용 구간 경계라 칼로리 만점") {
                    result.basis.calorie.calorieScore shouldBe 100
                }
            }

            When("칼로리가 목표의 110%면") {
                val result = DietScoreCalculator.scoreDay(2200.0, 275.0, 75.0, 67.0, targets)

                Then("허용 구간 경계라 칼로리 만점") {
                    result.basis.calorie.calorieScore shouldBe 100
                }
            }

            When("칼로리가 목표의 85%면") {
                val result = DietScoreCalculator.scoreDay(1700.0, 275.0, 75.0, 67.0, targets)

                Then("100 − 200 × 0.05 = 90점") {
                    result.basis.calorie.calorieScore shouldBe 90
                }
            }

            When("단백질을 목표의 2배 먹으면") {
                val result = DietScoreCalculator.scoreDay(2000.0, 275.0, 150.0, 67.0, targets)

                Then("단백질 초과는 감점하지 않는다 — 고기를 충분히 먹고 점수가 깎이면 안 된다") {
                    result.basis.macros
                        .first { it.name == "단백질" }
                        .score shouldBe 100
                    result.score shouldBe 100
                }
            }

            When("지방을 목표의 2배 넘게 먹으면") {
                val result = DietScoreCalculator.scoreDay(2000.0, 275.0, 75.0, 150.0, targets)

                Then("지방은 초과를 감점한다 — 매크로 평균 (100+100+0)/3, 하루 80점") {
                    result.basis.macros
                        .first { it.name == "지방" }
                        .score shouldBe 0
                    result.score shouldBe 80
                }
            }

            When("탄수화물이 목표의 절반이면") {
                val result = DietScoreCalculator.scoreDay(2000.0, 137.5, 75.0, 67.0, targets)

                Then("미달은 비례 점수 — 50점") {
                    result.basis.macros
                        .first { it.name == "탄수화물" }
                        .score shouldBe 50
                }
            }

            When("근거를 확인하면") {
                val result = DietScoreCalculator.scoreDay(2000.0, 275.0, 75.0, 67.0, targets)

                Then("자체 기준임을 밝히고 가중치를 함께 싣는다") {
                    result.basis.standard shouldBe "개인 목표 대비 총량 (자체 기준)"
                    result.basis.calorieWeight shouldBe 0.4
                    result.basis.macroWeight shouldBe 0.6
                    result.basis.calorie.targetKcal shouldBe 2000
                    result.basis.macros.size shouldBe 3
                }
            }
        }
    })
