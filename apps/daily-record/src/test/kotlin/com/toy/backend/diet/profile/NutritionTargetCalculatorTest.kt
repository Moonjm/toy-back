package com.toy.backend.diet.profile

import com.toy.backend.user.Gender
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

class NutritionTargetCalculatorTest :
    BehaviorSpec({
        val today = LocalDate.of(2026, 7, 29)

        Given("남성 · MODERATE · MAINTAIN") {
            When("175cm 70kg, 1990-01-01생(36세)") {
                val targets =
                    NutritionTargetCalculator.calculate(
                        gender = Gender.MALE,
                        birthDate = LocalDate.of(1990, 1, 1),
                        heightCm = 175.0,
                        weightKg = 70.0,
                        activityLevel = ActivityLevel.MODERATE,
                        goal = DietGoal.MAINTAIN,
                        today = today,
                    )

                Then("BMR 1618.75 × 1.55 = 2509kcal, KDRIs 55/15/30 배분") {
                    targets.kcal shouldBe 2509
                    targets.carbsG shouldBe 345
                    targets.proteinG shouldBe 94
                    targets.fatG shouldBe 84
                }
            }
        }

        Given("여성 · LIGHT · LOSE") {
            When("162cm 55kg, 1992-03-01생(34세)") {
                val targets =
                    NutritionTargetCalculator.calculate(
                        gender = Gender.FEMALE,
                        birthDate = LocalDate.of(1992, 3, 1),
                        heightCm = 162.0,
                        weightKg = 55.0,
                        activityLevel = ActivityLevel.LIGHT,
                        goal = DietGoal.LOSE,
                        today = today,
                    )

                Then("BMR 1231.5 × 1.375 × 0.85 = 1439kcal, KDRIs 50/20/30 배분") {
                    targets.kcal shouldBe 1439
                    targets.carbsG shouldBe 180
                    targets.proteinG shouldBe 72
                    targets.fatG shouldBe 48
                }
            }
        }

        Given("목표별 매크로 비율") {
            When("세 목표의 비율을 더하면") {
                Then("모두 100%이고 KDRIs 범위 안이다") {
                    DietGoal.entries.forEach { goal ->
                        (goal.carbsPercent + goal.proteinPercent + goal.fatPercent) shouldBe 100
                        (goal.carbsPercent in 50..65) shouldBe true
                        (goal.proteinPercent in 10..20) shouldBe true
                        (goal.fatPercent in 15..30) shouldBe true
                    }
                }
            }
        }
    })
