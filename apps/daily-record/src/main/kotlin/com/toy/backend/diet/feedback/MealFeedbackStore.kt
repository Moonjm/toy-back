package com.toy.backend.diet.feedback

import com.toy.backend.diet.meal.MealRepository
import com.toy.backend.diet.score.DietScoreCalculator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

private val log = KotlinLogging.logger {}

/**
 * 끼니 피드백의 DB 왕복 두 번을 **각각 짧은 트랜잭션**으로 끊는다. `DietFeedbackGenerator`가
 * 자기 메서드를 불러서는 프록시를 타지 않아 트랜잭션이 나뉘지 않으므로 별도 빈으로 둔다.
 *
 * **왜 나눠야 하는가** — LLM 호출을 트랜잭션 안에서 하면 `Meal` 엔티티가 느린 네트워크 호출
 * 내내 영속성 컨텍스트에 남는다. `Meal`에는 `@DynamicUpdate`도 `@Version`도 없어서 커밋 때
 * dirty check가 **전체 컬럼**을 쓰는데, 그 사이 사용자가 항목을 수정했다면 방금 계산된
 * `totalKcal`·탄단지·점수가 **호출 시작 시점의 값으로 되돌아간다.** `meal_item` 행은 새것인데
 * 합계만 옛것이 되어, 하루 점수와 주의 영양소 판정까지 함께 틀어진다.
 */
@Component
class MealFeedbackStore(
    private val mealRepository: MealRepository,
) {
    /** 프롬프트 문자열만 뽑아 나온다 — 엔티티를 밖으로 들고 나가지 않는 것이 이 분리의 요점이다. */
    @Transactional(readOnly = true)
    fun loadPrompt(mealId: Long): String? {
        val meal = mealRepository.findByIdOrNull(mealId)
        if (meal == null) {
            log.warn { "피드백 대상 끼니가 없다: id=$mealId" }
            return null
        }
        val basis = DietScoreCalculator.scoreMeal(meal.carbsG, meal.proteinG, meal.fatG).basis
        return DietFeedbackPrompts.meal(meal, basis)
    }

    /**
     * 문장을 싣는다. **여기서 다시 조회하므로** 다른 컬럼은 현재 값이고, 전체 컬럼을 써도 덮어쓸
     * 낡은 값이 없다. `feedback`이 null이면 `markFeedback`이 상태를 FAILED로 남긴다.
     */
    @Transactional
    fun publish(
        mealId: Long,
        feedback: String?,
    ) {
        val meal = mealRepository.findByIdOrNull(mealId) ?: return log.warn { "피드백을 실을 끼니가 없다: id=$mealId" }
        meal.markFeedback(feedback)
    }
}
