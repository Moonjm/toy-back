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
                block shouldContain "- 2026-07-30 (목) 58점 2930kcal"
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
                block shouldContain "- 2026-07-31 (금) 기록 없음"
            }

            // 7일치에 매크로까지 실으면 기준일 상세와 크기가 비슷해진다.
            Then("수량·매크로는 없다") {
                block shouldNotContain "탄 "
                block shouldNotContain "균형 근거"
            }
        }

        // 창이 7일이라 해가 바뀌는 주에는 **반드시** 두 해가 한 목록에 섞인다 — 매년 7일씩
        // 확정적으로 생긴다. 연도가 없으면 2026-01-02에 열었을 때 헤더는 2026인데 목록 앞쪽은
        // 2025년이라, 모델이 12-27을 11개월 뒤 미래로 읽을 여지가 남는다.
        //
        // 요일이 대신 막아주지도 않는다. 2025-12-27은 토요일이고 2026-12-27은 일요일이라,
        // 연도를 잘못 잡은 모델은 요일과 안 맞는 조합을 보게 되고 그때 뭘 버릴지는 알 수 없다.
        Given("직전 7일이 해를 넘어가면") {
            val block =
                DietChatPrompts.recentDaysBlock(
                    listOf(
                        RecentDaySummary(LocalDate.of(2025, 12, 27), 58, 2930.0, emptyList(), emptyList()),
                        RecentDaySummary(LocalDate.of(2026, 1, 1), 71, 2100.0, emptyList(), emptyList()),
                    ),
                )

            Then("줄마다 연도가 붙어 두 해가 구분된다") {
                block shouldContain "- 2025-12-27 (토)"
                block shouldContain "- 2026-01-01 (목)"
            }
        }

        Given("컨텍스트는") {
            val meals = listOf(lunch())
            val ctx = DietChatPrompts.context(date, meals, meals.totals(), targets, 61, 320, recent)

            Then("기준일이 먼저, 직전 7일이 뒤다") {
                ctx.indexOf("[2026-08-01 (토) 먹은 끼니]") shouldBeLessThan ctx.indexOf("[직전 2일]")
            }

            // 화면이 「점심 74점」을 보여주므로 「왜 그래?」가 반드시 온다. day()만으로는 못 답한다.
            // 라벨은 "[이번 끼니]"가 아니라 "[끼니 상세]"다 — 여러 끼니가 목록으로 나열되는
            // 자리라 "이번"이 어느 것도 가리키지 못한다(meal() 자체의 라벨은 끼니 피드백용이라
            // 그대로 둔다).
            Then("끼니별 상세가 들어간다 — 점수와 균형 근거까지") {
                ctx shouldContain "[끼니별 상세]"
                ctx shouldContain "[끼니 상세] 점심"
                ctx shouldNotContain "[이번 끼니]"
                ctx shouldContain "[균형 근거]"
            }
        }

        Given("끼니가 둘 이상이면") {
            val breakfast = dummyMeal(user = user, date = date, mealType = MealType.BREAKFAST)
            breakfast.replaceItems(listOf(dummyMealItem(meal = breakfast, foodName = "토스트")))
            breakfast.applyScore(84)
            val meals = listOf(breakfast, lunch())
            val ctx = DietChatPrompts.context(date, meals, meals.totals(), targets, 61, 320, recent)

            // 함정: 라벨을 안 바꾸면 "[이번 끼니]"가 목록 안에서 마지막 블록만 가리킬 여지가
            // 생겨, 「점심 47점은 왜?」 같은 질문에서 점수를 엉뚱한 끼니에 귀속시킬 수 있다.
            Then("끼니마다 각자 라벨을 갖고, 블록 사이에 빈 줄이 있다") {
                ctx shouldContain "[끼니 상세] 아침"
                ctx shouldContain "[끼니 상세] 점심"
                // 앞 블록 끝과 다음 라벨 사이에 빈 줄 하나 — 안 그러면 모델이 마지막 블록만
                // 「이번 끼니」로 붙들 여지가 생긴다.
                ctx shouldContain "\n\n[끼니 상세] 점심"
            }
        }

        Given("시스템 프롬프트는") {
            Then("범위 밖을 거절이 아니라 길 안내로 돌린다") {
                DietChatPrompts.SYSTEM_PROMPT shouldContain "그 날짜를 열어서"
            }

            // 「점수·열량과 음식 이름만 있습니다」라고 못 박아 두면, 눈앞에 주의 줄이 있는데도
            // 모델이 「그 정보는 없습니다」라고 답하거나 그 줄을 무시할 여지가 생긴다.
            Then("직전 7일에 무엇이 있는지가 실제 블록과 맞는다") {
                DietChatPrompts.recentDaysBlock(recent) shouldContain "· 주의: "
                DietChatPrompts.SYSTEM_PROMPT shouldContain "주의 영양소"
            }
        }
    })
