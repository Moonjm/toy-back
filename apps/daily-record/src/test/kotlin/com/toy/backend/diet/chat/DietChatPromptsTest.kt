package com.toy.backend.diet.chat

import com.toy.backend.diet.dietUser
import com.toy.backend.diet.dummyMeal
import com.toy.backend.diet.dummyMealItem
import com.toy.backend.diet.feedback.totals
import com.toy.backend.diet.meal.Meal
import com.toy.backend.diet.meal.MealType
import com.toy.backend.diet.profile.NutritionTargets
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.time.LocalDate

class DietChatPromptsTest :
    BehaviorSpec({
        val user = dietUser()
        val date = LocalDate.of(2026, 8, 1)
        val targets = NutritionTargets(2509, 345, 94, 84, 125, 2300, 30)

        fun lunch(): Meal {
            val meal = dummyMeal(user = user, date = date, mealType = MealType.LUNCH)
            meal.replaceItems(listOf(dummyMealItem(meal = meal, foodName = "제육볶음")))
            meal.applyScore(74)
            return meal
        }

        val recent =
            listOf(
                RecentDaySummary(
                    date = LocalDate.of(2026, 7, 30),
                    dayScore = 58,
                    totalKcal = 2930.0,
                    warnings = listOf("나트륨 4200mg(기준 2300mg 이하)"),
                    meals = listOf(MealType.DINNER to listOf("치킨", "맥주")),
                ),
                RecentDaySummary(
                    date = LocalDate.of(2026, 7, 31),
                    dayScore = null,
                    totalKcal = 0.0,
                    warnings = emptyList(),
                    meals = emptyList(),
                ),
            )

        Given("직전 7일 블록은") {
            val block = DietChatPrompts.recentDaysBlock(recent)

            Then("하루 한 줄에 점수·열량이 들어간다") {
                block shouldContain "- 07-30 (목) 58점 2930kcal"
            }

            // 점수가 보이면 이유를 묻는다(함정 2-1). 이름이 없으면 모델이 지어낸다.
            Then("끼니별 음식 이름이 한글 끼니명과 함께 붙는다") {
                block shouldContain "저녁: 치킨, 맥주"
                block shouldNotContain "DINNER"
            }

            // 기준을 함께 실어야 모델이 많은지 적은지 스스로 판단하지 않는다.
            Then("주의 영양소는 기준값까지 함께 싣는다") {
                block shouldContain "나트륨 4200mg(기준 2300mg 이하)"
            }

            // 빼 버리면 모델이 날짜가 연속인 줄 알고 「이틀 연속 좋았다」처럼 없는 추세를 만든다.
            Then("기록 없는 날도 자리를 지킨다") {
                block shouldContain "- 07-31 (금) 기록 없음"
            }

            // 7일치에 매크로까지 실으면 기준일 상세와 크기가 비슷해진다.
            Then("수량·매크로는 없다") {
                block shouldNotContain "탄 "
                block shouldNotContain "균형 근거"
            }
        }

        Given("컨텍스트는") {
            val meals = listOf(lunch())
            val ctx = DietChatPrompts.context(date, meals, meals.totals(), targets, 61, 320, recent)

            Then("기준일이 먼저, 직전 7일이 뒤다") {
                ctx.indexOf("[2026-08-01 (토) 먹은 끼니]") shouldBeLessThan ctx.indexOf("[직전 2일]")
            }

            // 화면이 「점심 74점」을 보여주므로 「왜 그래?」가 반드시 온다. day()만으로는 못 답한다.
            Then("끼니별 상세가 들어간다 — 점수와 균형 근거까지") {
                ctx shouldContain "[끼니별 상세]"
                ctx shouldContain "[이번 끼니] 점심"
                ctx shouldContain "[균형 근거]"
            }
        }

        Given("시스템 프롬프트는") {
            Then("범위 밖을 거절이 아니라 길 안내로 돌린다") {
                DietChatPrompts.SYSTEM_PROMPT shouldContain "그 날짜를 열어서"
            }
        }
    })
