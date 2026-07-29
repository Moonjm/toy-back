package com.toy.backend.diet.feedback

import com.toy.backend.diet.daily.DailyActivityRepository
import com.toy.backend.diet.llm.OpenRouterClient
import com.toy.backend.diet.meal.Meal
import com.toy.backend.diet.meal.MealRepository
import com.toy.backend.diet.profile.NutritionTargets
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.repository.findByIdOrNull
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

private val log = KotlinLogging.logger {}

/**
 * 텍스트 모델로 문장만 만든다. 이미지 호출이 비싼 부분이고 텍스트 호출은 훨씬 저렴하므로,
 * 정확한 수치를 얻은 뒤 2차로 나눠 부르는 비용이 크지 않다.
 */
@Component
class DietFeedbackGenerator(
    private val mealRepository: MealRepository,
    private val activityRepository: DailyActivityRepository,
    @Autowired(required = false) private val client: OpenRouterClient?,
) {
    /**
     * 끼니 피드백은 **확정 시점**에 만든다. 인식 직후가 아니라 사용자가 항목을 고친 뒤라야
     * 실제로 먹은 것에 대한 조언이 된다. `@Async`라 엔티티가 아닌 id를 받아 다시 조회한다.
     */
    @Async
    @Transactional
    fun generateForMeal(mealId: Long) {
        val meal = mealRepository.findByIdOrNull(mealId) ?: return log.warn { "피드백 대상 끼니가 없다: id=$mealId" }
        val openRouter = client
        if (openRouter == null) {
            log.warn { "OpenRouter 미설정 — 끼니 피드백을 건너뛴다: id=$mealId" }
            return meal.markFeedback(null)
        }

        // 누적치를 함께 넘기므로 한 끼만 보고 말하지 않고 하루 맥락이 담긴 조언이 나온다.
        val sameDay = mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(meal.user, meal.date)
        val cumulative = sameDay.filter { !it.createdAt.isAfter(meal.createdAt) }.totals()
        val activeEnergy = activityRepository.findByUserAndDate(meal.user, meal.date)?.activeEnergyKcal

        val prompt = DietFeedbackPrompts.meal(meal, cumulative, meal.targets(), activeEnergy)
        meal.markFeedback(openRouter.generateText(DietFeedbackPrompts.SYSTEM_PROMPT, prompt))
    }

    /** 하루 마감 피드백. 호출자가 캐시를 관리하므로 여기서는 문장만 만들어 돌려준다(실패 시 null). */
    fun generateForDay(
        meals: List<Meal>,
        totals: NutritionTotals,
        targets: NutritionTargets,
        dayScore: Int,
        activeEnergyKcal: Int?,
    ): String? {
        val openRouter = client ?: return null
        return openRouter.generateText(
            DietFeedbackPrompts.SYSTEM_PROMPT,
            DietFeedbackPrompts.day(meals, totals, targets, dayScore, activeEnergyKcal),
        )
    }
}
