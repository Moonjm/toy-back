package com.toy.backend.diet.feedback

import com.toy.backend.diet.daily.DailyActivityRepository
import com.toy.backend.diet.meal.MealRepository
import com.toy.backend.diet.score.DietScoreCalculator
import com.toy.backend.user.UserRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime

private val log = KotlinLogging.logger {}

/**
 * 하루 피드백의 DB 왕복 두 번을 **각각 짧은 트랜잭션**으로 끊는다. `MealFeedbackStore`와 같은
 * 이유이고 같은 모양이다 — `DietFeedbackGenerator`가 자기 메서드를 불러서는 프록시를 타지 않아
 * 트랜잭션이 나뉘지 않으므로 별도 빈으로 둔다.
 *
 * **왜 나눠야 하는가** — 예전에는 `generateForDay` 전체가 하나의 `@Transactional`이라 LLM 호출
 * 내내 DB 커넥션을 붙들었다. 수 초짜리 네트워크 호출 동안 커넥션 하나가 묶여 있는 것이고,
 * 그 사이 `Meal`도 영속성 컨텍스트에 남는다. 끼니 쪽은 같은 이유로 이미 쪼개 두었는데
 * 여기만 남아 있었다.
 *
 * `Meal`이 호출 내내 남아 있으면 그 사이 항목이 수정됐을 때 커밋의 dirty check가 합계 컬럼을
 * 옛 값으로 되돌린다 — `MealFeedbackStore` 주석에 그 사고 기록이 있다.
 */
@Component
class DayFeedbackStore(
    private val userRepository: UserRepository,
    private val mealRepository: MealRepository,
    private val activityRepository: DailyActivityRepository,
    private val feedbackRepository: DailyDietFeedbackRepository,
) {
    /** 프롬프트와 하루 점수만 뽑아 나온다 — 엔티티를 밖으로 들고 나가지 않는 것이 이 분리의 요점이다. */
    @Transactional(readOnly = true)
    fun loadPrompt(
        userId: Long,
        date: LocalDate,
    ): DayPrompt? {
        val user = userRepository.findByIdOrNull(userId)
        if (user == null) {
            log.warn { "피드백 대상 사용자가 없다: id=$userId" }
            return null
        }
        val meals = mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date)
        // 마커를 쓴 뒤 끼니가 모두 삭제된 좁은 창 — 캐시 행은 delete 경로에서 이미 지워졌다.
        if (meals.isEmpty()) return null

        val totals = meals.totals()
        val targets = meals.first().targets()
        val dayScore = DietScoreCalculator.scoreDay(totals.kcal, totals.carbsG, totals.proteinG, totals.fatG, targets).score
        val activeEnergyKcal = activityRepository.findByUserAndDate(user, date)?.activeEnergyKcal
        return DayPrompt(
            prompt = DietFeedbackPrompts.day(meals, totals, targets, dayScore, activeEnergyKcal),
            dayScore = dayScore,
        )
    }

    /**
     * 문장을 싣는다.
     *
     * **마커가 없으면(`cached == null`) 새로 저장하지 않고 버린다.** 마커는 트리거 직전에 항상
     * 저장되므로, 여기서 사라졌다는 것은 그 사이에 끼니 삭제·활동 에너지 갱신으로 캐시가
     * 무효화됐다는 뜻이다 — 방금 만든 문장은 이미 낡은 구성을 기준으로 한 것이다.
     *
     * **[markerAt]이 지금 값과 다르면 버린다.** 그 검사가 왜 필요한지는
     * `DietFeedbackGenerator.generateForDay` 주석 참고.
     */
    @Transactional
    fun publish(
        userId: Long,
        date: LocalDate,
        markerAt: LocalDateTime,
        dayScore: Int,
        feedback: String,
    ) {
        val user = userRepository.findByIdOrNull(userId) ?: return log.warn { "피드백 대상 사용자가 없다: id=$userId" }
        val cached = feedbackRepository.findByUserAndDate(user, date) ?: return
        if (cached.generatedAt != markerAt) {
            return log.info { "그 사이 새 마커가 찍혀 낡은 하루 피드백을 버린다: date=$date, 내 마커=$markerAt, 지금=${cached.generatedAt}" }
        }
        // `generatedAt`을 갱신하지 않는다. 마커를 찍은 시각으로 남겨야, 생성 중에 끼니가 수정된
        // 경우 무효화 판정에 걸려 다음 조회가 다시 만든다.
        cached.publish(dayScore, feedback)
    }
}

/** 프롬프트와, 그것을 만들 때 함께 구한 하루 점수. 점수를 다시 계산하지 않으려고 같이 들고 나온다. */
data class DayPrompt(
    val prompt: String,
    val dayScore: Int,
)
