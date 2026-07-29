package com.toy.backend.diet.feedback

import com.toy.backend.diet.daily.DailyActivityRepository
import com.toy.backend.diet.llm.OpenRouterClient
import com.toy.backend.diet.meal.MealRepository
import com.toy.backend.diet.score.DietScoreCalculator
import com.toy.backend.user.UserRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.repository.findByIdOrNull
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime

private val log = KotlinLogging.logger {}

/**
 * 텍스트 모델로 문장만 만든다. 이미지 호출이 비싼 부분이고 텍스트 호출은 훨씬 저렴하므로,
 * 정확한 수치를 얻은 뒤 2차로 나눠 부르는 비용이 크지 않다.
 */
@Component
class DietFeedbackGenerator(
    private val mealRepository: MealRepository,
    private val activityRepository: DailyActivityRepository,
    private val feedbackRepository: DailyDietFeedbackRepository,
    private val userRepository: UserRepository,
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

    /**
     * 하루 마감 피드백. **호출자(`DailyDietService`)가 생성을 시작하기 전에 `DailyDietFeedback`
     * 행을 `feedback = null`로 먼저 써 둔다** — "생성이 이미 걸렸다"는 표시라 폴링이 호출을 중복시키지
     * 않는다. `@Async`라 엔티티가 아닌 id로 받아 사용자를 다시 조회한다.
     *
     * **생성이 실패하면(`generateText`가 null) 행을 그대로 둔다.** `feedback`은 null로 남고
     * `generatedAt`은 마커를 쓴 시각이라, 끼니가 바뀌기 전까지(캐시 무효화 전까지) 재호출되지 않는다
     * — 자동 재시도를 넣지 않는다는 설계와 맞물리는 지점이다.
     *
     * **성공했는데도 마커가 없으면(`cached == null`) 새로 저장하지 않고 버린다.** 마커는 트리거
     * 직전에 항상 저장되므로, 여기서 사라졌다는 것은 그 사이에 끼니 삭제·활동 에너지 갱신으로
     * 캐시가 무효화됐다는 뜻이다 — 방금 만든 문장은 이미 낡은 구성을 기준으로 한 것이다.
     */
    @Async
    @Transactional
    fun generateForDay(
        userId: Long,
        date: LocalDate,
    ) {
        val openRouter = client ?: return
        val user = userRepository.findByIdOrNull(userId) ?: return log.warn { "피드백 대상 사용자가 없다: id=$userId" }
        val meals = mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date)
        // 마커를 쓴 뒤 끼니가 모두 삭제된 좁은 창 — 캐시 행은 delete 경로에서 이미 지워졌다.
        if (meals.isEmpty()) return

        val totals = meals.totals()
        val targets = meals.first().targets()
        val dayScore = DietScoreCalculator.scoreDay(totals.kcal, totals.carbsG, totals.proteinG, totals.fatG, targets).score
        val activeEnergyKcal = activityRepository.findByUserAndDate(user, date)?.activeEnergyKcal

        val generated =
            openRouter.generateText(
                DietFeedbackPrompts.SYSTEM_PROMPT,
                DietFeedbackPrompts.day(meals, totals, targets, dayScore, activeEnergyKcal),
            ) ?: return

        // 마커는 트리거 직전에 항상 저장돼 있다. 그런데도 여기서 못 찾았다면 그 사이에 누가
        // 지운 것이다(끼니 삭제·활동 에너지 갱신 — 둘 다 캐시를 지운다) — 즉 지금 막 만든 문장은
        // 이미 낡은 끼니 구성을 기준으로 한 것이라는 뜻이다. 되살리지 않고 버린다. 다음 조회가
        // 새 마커를 만들어 자연히 다시 시도한다.
        val cached = feedbackRepository.findByUserAndDate(user, date) ?: return
        cached.update(dayScore, generated, LocalDateTime.now())
    }
}
