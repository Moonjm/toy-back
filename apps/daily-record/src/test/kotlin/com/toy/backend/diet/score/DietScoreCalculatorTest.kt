package com.toy.backend.diet.score

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class DietScoreCalculatorTest :
    BehaviorSpec({
        Given("끼니 점수 — KDRIs 범위를 벗어난 만큼만 감점") {
            When("탄 75% · 단 8% · 지 17% (설계 문서 예시)") {
                // 168.75×4 + 18×4 + 17×9 = 675 + 72 + 153 = 900kcal
                val result = DietScoreCalculator.scoreMeal(carbsG = 168.75, proteinG = 18.0, fatG = 17.0)

                Then("초과 10%p + 미달 2%p → 100 − 1.0 × 12 = 88점") {
                    result.score shouldBe 88
                }

                Then("근거에 항목별 status와 penalty가 실린다") {
                    val macros = result.basis!!.macros
                    macros[0].name shouldBe "탄수화물"
                    macros[0].percent shouldBe 75.0
                    macros[0].status shouldBe MacroStatus.OVER
                    macros[0].penalty shouldBe 10.0
                    macros[1].status shouldBe MacroStatus.UNDER
                    macros[1].penalty shouldBe 2.0
                    macros[2].status shouldBe MacroStatus.IN_RANGE
                    macros[2].penalty shouldBe 0.0
                }

                Then("근거의 감점 합이 실제 점수와 일치한다") {
                    val total = result.basis!!.macros.sumOf { it.penalty }
                    result.score shouldBe (100 - total).toInt()
                }
            }

            When("KDRIs 범위 하한 경계 — 탄 50 · 단 20 · 지 30") {
                // 112.5×4 + 45×4 + 30×9 = 450 + 180 + 270 = 900kcal
                val result = DietScoreCalculator.scoreMeal(carbsG = 112.5, proteinG = 45.0, fatG = 30.0)

                Then("경계는 범위 안이라 100점") {
                    result.score shouldBe 100
                    result.basis!!.macros.all { it.status == MacroStatus.IN_RANGE } shouldBe true
                }
            }

            When("KDRIs 범위 상한 경계 — 탄 65 · 단 10 · 지 25") {
                // 146.25×4 + 22.5×4 + 25×9 = 585 + 90 + 225 = 900kcal
                val result = DietScoreCalculator.scoreMeal(carbsG = 146.25, proteinG = 22.5, fatG = 25.0)

                Then("경계는 범위 안이라 100점") {
                    result.score shouldBe 100
                }
            }

            // 세 비율은 합이 100%라 서로 독립이 아니다 — 탄수화물이 높으면 나머지 둘은 필연적으로
            // 낮아지고, 감점을 그냥 더하면 하나의 불균형을 두 번 센다(기울기를 1.0으로 내린 이유).
            // 기울기가 2.0이던 시절에는 이 끼니가 0점이라 「순수 지방」과 구분되지 않았다.
            When("밥만 먹었을 때 — 탄 100 · 단 0 · 지 0") {
                val result = DietScoreCalculator.scoreMeal(carbsG = 100.0, proteinG = 0.0, fatG = 0.0)

                Then("치우쳤지만 바닥은 아니다 — 35 + 10 + 15 감점으로 40점") {
                    result.score shouldBe 40
                }
            }

            When("탄수화물이 아예 없을 때 — 지방만 100g") {
                val result = DietScoreCalculator.scoreMeal(carbsG = 0.0, proteinG = 0.0, fatG = 100.0)

                Then("감점이 100을 넘어도 0점 아래로 내려가지 않는다") {
                    result.score shouldBe 0
                }
            }

            When("물·커피처럼 매크로가 0이면") {
                val result = DietScoreCalculator.scoreMeal(carbsG = 0.0, proteinG = 0.0, fatG = 0.0)

                Then("비율을 정의할 수 없으므로 점수도 근거도 null") {
                    result.score shouldBe null
                    result.basis shouldBe null
                }
            }

            When("기준 문구를 확인하면") {
                val result = DietScoreCalculator.scoreMeal(carbsG = 100.0, proteinG = 30.0, fatG = 20.0)

                Then("응답에 국가 기준 이름이 실린다") {
                    result.basis!!.standard shouldBe "2025 한국인 영양소 섭취기준(KDRIs) 에너지적정비율"
                }
            }
        }
    })
