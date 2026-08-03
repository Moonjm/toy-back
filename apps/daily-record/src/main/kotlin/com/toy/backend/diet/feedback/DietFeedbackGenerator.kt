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
    private val store: MealFeedbackStore,
    @Autowired(required = false) private val client: OpenRouterClient?,
) {
    /**
     * `MealAnalyzer.isAvailable`과 같은 뜻이다 — 키가 없으면 빈이 등록되지 않는다.
     * **수동 재시도를 받는 쪽이 이걸 보고 미리 거절해야 한다** — 안 그러면 204를 돌려주고도
     * 여기서 `publish(null)`로 다시 FAILED가 되어, 사용자는 성공했다고 알고 또 누른다.
     */
    val isAvailable: Boolean get() = client != null

    /**
     * 끼니 피드백은 **확정 시점**에 만든다. 인식 직후가 아니라 사용자가 항목을 고친 뒤라야
     * 실제로 먹은 것에 대한 조언이 된다. `@Async`라 엔티티가 아닌 id를 받아 다시 조회한다.
     *
     * **이 끼니만 보고 쓴다 — 하루 맥락(누적 섭취량·하루 목표·활동 에너지)은 넣지 않는다.**
     * 끼니 점수가 이미 「끼니는 균형, 하루는 목표 대비 총량」으로 역할을 나눠 놨는데, 프롬프트가
     * 하루 맥락을 실으면 앞선 끼니를 고치거나 지울 때 이 끼니의 피드백까지 낡는다(직접 바뀐
     * 끼니만 재생성하므로 아무도 그걸 고쳐주지 않는다). 종합은 하루 마감 피드백의 몫으로 남긴다.
     */
    @Async
    fun generateForMeal(mealId: Long) {
        // **트랜잭션 밖에서 호출한다.** 안에서 부르면 Meal 엔티티가 호출 내내 영속성 컨텍스트에
        // 남고, 그 사이 항목이 수정되면 커밋 때 dirty check가 합계 컬럼을 옛 값으로 되돌린다.
        //
        // 키가 없을 때도 먼저 읽는다 — `publish`가 「그 사이 안 바뀌었는가」를 판별할 판이 필요하고,
        // 끼니가 이미 지워졌으면 FAILED로 남길 이유도 없다.
        val loaded = store.loadPrompt(mealId) ?: return
        val openRouter = client
        if (openRouter == null) {
            log.warn { "OpenRouter 미설정 — 끼니 피드백을 건너뛴다: id=$mealId" }
            return store.publish(mealId, loaded.version, null)
        }
        store.publish(mealId, loaded.version, openRouter.generateText(DietFeedbackPrompts.SYSTEM_PROMPT, loaded.prompt))
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
     *
     * **그 검사는 캐시 행을 지우는 경로만 잡는다.** 항목 수정은 행을 지우지 않고 `Meal.updatedAt`만
     * 올리므로 여기 걸리지 않는다. 그래서 [markerAt]을 함께 받아 **내가 찍은 마커가 아직 그대로인지**
     * 확인한다(아래 publish 직전 주석).
     */
    @Async
    @Transactional
    fun generateForDay(
        userId: Long,
        date: LocalDate,
        markerAt: LocalDateTime,
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

        // **내가 찍은 마커가 아직 그대로일 때만 싣는다.**
        //
        // 생성 중에 끼니가 수정되면 다음 하루 조회가 무효화 판정에 걸려 **새 마커를 찍고**
        // 새 작업을 건다(`DailyDietService.resolveFeedback`). 그때 `generatedAt`은 그 수정보다
        // 뒤로 점프하는데, 이 낡은 작업이 그 행에 문장을 실으면 **낡은 문장이 새 마커를 뒤집어쓰고
        // 유효한 캐시로 굳는다.** 아래 `publish`가 `generatedAt`을 안 건드리는 것만으로는 못 막는
        // 경로다 — 시각을 새로 찍는 쪽이 내가 아니라 다음 조회이기 때문이다.
        //
        // 늦게 끝난 낡은 작업이 먼저 끝난 새 문장을 덮어쓰는 것도 같이 막힌다.
        if (cached.generatedAt != markerAt) {
            return log.info { "그 사이 새 마커가 찍혀 낡은 하루 피드백을 버린다: date=$date, 내 마커=$markerAt, 지금=${cached.generatedAt}" }
        }
        // `generatedAt`을 갱신하지 않는다. 마커를 찍은 시각으로 남겨야, 생성 중에 끼니가 수정된
        // 경우 무효화 판정에 걸려 다음 조회가 다시 만든다.
        cached.publish(dayScore, generated)
    }
}
