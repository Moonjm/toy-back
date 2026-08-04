package com.toy.backend.diet.meal

import com.toy.backend.diet.NutritionSource
import com.toy.backend.diet.dietUser
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * `contentUpdatedAt`은 **하루 피드백 캐시의 무효화 기준**이다(`DailyDietService.resolveFeedback`).
 *
 * 양쪽으로 다 틀릴 수 있는 값이라 두 방향을 함께 건다 — 안 오르면 항목을 고쳐도 하루 피드백이
 * 낡은 채로 굳고, 아무 때나 오르면 `updatedAt`을 쓰던 때와 똑같이 유료 호출이 매번 한 번씩
 * 더 나간다.
 */
class MealContentVersionTest :
    BehaviorSpec({
        val user = dietUser()
        val past = LocalDateTime.of(2026, 7, 29, 8, 0)

        fun meal(): Meal =
            Meal(
                user = user,
                date = LocalDate.of(2026, 7, 29),
                mealType = MealType.LUNCH,
                weightKg = 70.0,
                targetKcal = 2000,
                targetCarbsG = 275,
                targetProteinG = 75,
                targetFatG = 67,
                targetSugarG = 100,
                targetSodiumMg = 2300,
                targetFiberG = 30,
            ).also { it.contentUpdatedAt = past }

        fun item(
            meal: Meal,
            name: String,
        ) = MealItem(
            meal = meal,
            foodName = name,
            foodCode = "D1",
            quantityG = 200.0,
            kcal = 400.0,
            carbsG = 50.0,
            proteinG = 20.0,
            fatG = 12.0,
            source = NutritionSource.DB_MATCHED,
        )

        Given("항목을 교체하면") {
            val meal = meal()
            meal.replaceItems(listOf(item(meal, "제육볶음")))

            Then("내용 판이 올라간다 — 안 오르면 하루 피드백이 낡은 채로 굳는다") {
                meal.contentUpdatedAt shouldBeGreaterThan past
            }
        }

        Given("항목을 얹으면") {
            val meal = meal()
            meal.addItems(listOf(item(meal, "김치찌개")))

            Then("내용 판이 올라간다 — 병합 확정이 이 경로다") {
                meal.contentUpdatedAt shouldBeGreaterThan past
            }
        }

        // 아래 셋은 하루 프롬프트가 읽지 않는 값들이다(`DietFeedbackPrompts.day`).
        // 여기서 판이 오르면 하루 피드백이 헛되이 다시 만들어진다 — 고치기 전 결함이 정확히 그것이었다.
        Given("피드백만 실으면") {
            val meal = meal()
            meal.markFeedback("잘 드셨어요.")

            Then("내용 판은 그대로다 — 하루 프롬프트는 피드백을 읽지 않는다") {
                meal.contentUpdatedAt shouldBe past
            }
        }

        Given("피드백을 대기 상태로 되돌리면") {
            val meal = meal()
            meal.markFeedbackPending()

            Then("내용 판은 그대로다 — 재시도가 하루 캐시를 깨우면 안 된다") {
                meal.contentUpdatedAt shouldBe past
            }
        }

        Given("사진만 넣거나 빼면") {
            val meal = meal()
            meal.addPhoto(MealPhoto(meal = meal, fileId = 1L, sortOrder = 0))
            meal.removePhoto(1L)

            Then("내용 판은 그대로다 — 하루 프롬프트에 사진이 안 들어간다") {
                meal.contentUpdatedAt shouldBe past
            }
        }

        Given("점수만 다시 매기면") {
            val meal = meal()
            meal.applyScore(74)

            Then("내용 판은 그대로다 — 점수는 항목에서 나오므로 항목 변경이 이미 판을 올린다") {
                meal.contentUpdatedAt shouldBe past
            }
        }

        // 끼니 종류는 항목이 아니지만 하루 프롬프트가 읽는다(`DietFeedbackPrompts.day`).
        // 판단 기준이 「항목이 바뀌었나」가 아니라 「하루 프롬프트가 읽는 값이 바뀌었나」라는 것을
        // 이 둘이 함께 못 박는다.
        Given("끼니 종류를 바꾸면") {
            val meal = meal()
            meal.changeMealType(MealType.DINNER)

            Then("내용 판이 올라간다 — 안 오르면 하루 피드백이 「간식: 치킨」인 채로 남는다") {
                meal.mealType shouldBe MealType.DINNER
                meal.contentUpdatedAt shouldBeGreaterThan past
            }
        }

        Given("같은 종류로 다시 바꾸면") {
            val meal = meal()
            meal.changeMealType(MealType.LUNCH)

            Then("내용 판은 그대로다 — 값이 안 바뀌었으면 하루 피드백을 다시 만들 이유가 없다") {
                meal.contentUpdatedAt shouldBe past
            }
        }
    })
