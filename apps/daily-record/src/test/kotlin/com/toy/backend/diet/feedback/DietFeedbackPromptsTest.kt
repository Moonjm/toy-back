package com.toy.backend.diet.feedback

import com.toy.backend.diet.dietUser
import com.toy.backend.diet.dummyMeal
import com.toy.backend.diet.dummyMealItem
import com.toy.backend.diet.meal.MealType
import com.toy.backend.diet.meal.toResponse
import com.toy.backend.diet.profile.NutritionTargets
import com.toy.backend.diet.score.DietScoreCalculator
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.time.LocalDate

/**
 * 프롬프트 문자열에 걸린 첫 그물이다. 여기 담긴 것들은 전부 **모델이 지어내는 것을 막으려고**
 * 하나씩 붙은 값이라(`DietFeedbackPrompts` 주석), 조용히 빠져도 컴파일도 테스트도 안 깨졌다.
 */
class DietFeedbackPromptsTest :
    BehaviorSpec({
        val user = dietUser()
        val date = LocalDate.of(2026, 8, 1)

        fun lunch(): com.toy.backend.diet.meal.Meal {
            val meal = dummyMeal(user = user, date = date, mealType = MealType.LUNCH)
            meal.replaceItems(listOf(dummyMealItem(meal = meal, foodName = "제육볶음")))
            meal.applyScore(74)
            return meal
        }

        val targets = NutritionTargets(2509, 345, 94, 84, 125, 2300, 30)

        Given("하루 프롬프트는") {
            val meals = listOf(lunch())
            val prompt = DietFeedbackPrompts.day(date, meals, meals.totals(), targets, 61, 320)

            // 날짜가 없으면 모델은 어느 날 얘기인지 모른다. 지난 날짜 대화에서도 「오늘」이라 말하고,
            // 자기가 보고 있는 게 그 날짜인데도 「오늘 기록만 볼 수 있다」고 거절할 수 있다.
            Then("헤더에 기준 날짜와 요일이 박힌다 — 「오늘」이 아니다") {
                prompt shouldContain "[2026-08-01 (토) 먹은 끼니]"
                prompt shouldNotContain "오늘 먹은 끼니"
            }

            // 사용자가 한국어로 묻고 모델이 한국어로 답한다. 컨텍스트만 영어면 한 홉을 건너뛰어야 하고,
            // 모델이 근거를 인용할 때 「LUNCH에 드신」처럼 새어 나온다.
            Then("끼니 종류가 한글이다") {
                prompt shouldContain "- 점심: 제육볶음"
                prompt shouldNotContain "LUNCH"
            }

            Then("기준값을 함께 싣는다 — 없으면 모델이 많은지 적은지 스스로 판단한다") {
                prompt shouldContain "[목표]"
                prompt shouldContain "[주의 영양소]"
                prompt shouldContain "기준 2300mg 이하"
            }
        }

        Given("끼니 프롬프트는") {
            val meal = lunch()
            val prompt =
                DietFeedbackPrompts.meal(
                    meal,
                    DietScoreCalculator.scoreMeal(meal.carbsG, meal.proteinG, meal.fatG),
                )

            Then("끼니 종류가 한글이다") {
                prompt shouldContain "[이번 끼니] 점심"
                prompt shouldNotContain "LUNCH"
            }

            // 점수는 저장된 `Meal.score` 컬럼에서, 근거는 그때 재계산해서 오면 한 블록 안에서
            // 둘이 어긋난다. 이 저장소는 감점 기울기를 2.0 → 1.0으로 한 번 바꿨고 저장된 점수를
            // 백필하지 않았다 — 그 이전 끼니는 지금도 컬럼 값이 낡아 있다.
            // `MealDtos.toResponse`가 같은 이유로 점수와 근거를 함께 재계산한다.
            Then("점수와 근거가 같은 계산에서 나온다 — 저장된 컬럼이 아니다") {
                val scored = DietScoreCalculator.scoreMeal(meal.carbsG, meal.proteinG, meal.fatG)
                prompt shouldContain "이번 끼니 균형 점수: ${scored.score}"
                // 픽스처의 저장된 값이다. 재계산 값과 달라야 이 테스트가 무엇을 가르는지가 있다.
                meal.score shouldBe 74
                prompt shouldNotContain "균형 점수: 74"
            }
        }

        // 프롬프트를 한글로 바꾸면서 응답까지 바꾸면 iOS의 디코딩이 깨진다.
        Given("API 응답은") {
            Then("끼니 종류가 enum 이름 그대로다 — iOS 계약이다") {
                lunch().toResponse(emptyMap()).mealType.name shouldBe "LUNCH"
            }
        }
    })
