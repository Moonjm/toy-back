package com.toy.backend.diet.daily

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.NutritionSource
import com.toy.backend.diet.feedback.DailyDietFeedback
import com.toy.backend.diet.feedback.DailyDietFeedbackRepository
import com.toy.backend.diet.feedback.DietFeedbackGenerator
import com.toy.backend.diet.feedback.totals
import com.toy.backend.diet.meal.Meal
import com.toy.backend.diet.meal.MealRepository
import com.toy.backend.diet.meal.toResponse
import com.toy.backend.diet.runAfterCommit
import com.toy.backend.diet.score.DietScoreCalculator
import com.toy.backend.file.FileService
import com.toy.backend.user.User
import com.toy.backend.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime

@Service
@Transactional(readOnly = true)
class DailyDietService(
    private val mealRepository: MealRepository,
    private val feedbackRepository: DailyDietFeedbackRepository,
    private val activityRepository: DailyActivityRepository,
    private val userRepository: UserRepository,
    private val feedbackGenerator: DietFeedbackGenerator,
    private val fileService: FileService,
) {
    /**
     * 조회지만 피드백 캐시를 lazy 생성·갱신하므로 쓰기 트랜잭션이다. **LLM은 여기서 부르지 않는다**
     * — 캐시가 유효하면 그 문장을 싣고, 무효하거나 없으면 `feedback = null`로 즉시 응답한 뒤
     * 뒤에서 생성을 시작한다. 동기로 부르면 OpenRouter가 죽어 있을 때 조회마다 호출이 나가고,
     * 쓰기 트랜잭션이 요청 스레드를 최대 `openrouter.timeout-seconds`(60초)까지 붙잡는다.
     */
    @Transactional
    fun getDay(
        username: String,
        date: LocalDate,
    ): DayResponse {
        val user = findUser(username)
        val meals = mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date)
        val activeEnergyKcal = activityRepository.findByUserAndDate(user, date)?.activeEnergyKcal
        val urls = fileService.getPresignedUrls(meals.flatMap { meal -> meal.photos.map { it.fileId } })

        if (meals.isEmpty()) {
            return DayResponse(
                date = date,
                dayScore = null,
                scoreBasis = null,
                feedback = null,
                totalKcal = 0.0,
                carbsG = 0.0,
                proteinG = 0.0,
                fatG = 0.0,
                activeEnergyKcal = activeEnergyKcal,
                meals = emptyList(),
                nutrientLimits = emptyList(),
                estimatedItemCount = 0,
            )
        }

        val totals = meals.totals()
        // 하루 목표는 **그날 첫 끼니의 스냅샷**에서 읽는다. 현재 프로필을 쓰면 오늘 몸무게를
        // 갱신했을 때 지난주 하루 점수가 같이 바뀐다. 목표를 뽑는 정렬(createdAt 오름차순)은
        // 응답에 담을 끼니 순서와는 별개다 — 섞지 않는다.
        val targets = meals.first().targets()
        val dayScore = DietScoreCalculator.scoreDay(totals.kcal, totals.carbsG, totals.proteinG, totals.fatG, targets)
        val feedback = resolveFeedback(user, date, meals, dayScore.score)
        val nutrientLimits = NutrientLimitEvaluator.evaluate(totals, targets)

        return DayResponse(
            date = date,
            dayScore = dayScore.score,
            scoreBasis = dayScore.basis,
            feedback = feedback,
            totalKcal = totals.kcal,
            carbsG = totals.carbsG,
            proteinG = totals.proteinG,
            fatG = totals.fatG,
            activeEnergyKcal = activeEnergyKcal,
            // 응답에 담을 때만 끼니 순(아침→점심→저녁→간식)으로 정렬한다 — 확정한 순서(createdAt)
            // 그대로 실으면 저녁을 먼저 확정한 날 화면이 저녁→아침으로 나온다.
            meals = meals.sortedBy { it.mealType }.map { it.toResponse(urls) },
            nutrientLimits = nutrientLimits,
            // 판정에 「이 숫자는 추정이 섞였다」를 달아 주는 값이다. 미매칭 항목도 이제 LLM 추정
            // 수치를 갖지만, 그 오차까지 사라지는 것은 아니다.
            estimatedItemCount = meals.sumOf { meal -> meal.items.count { it.source == NutritionSource.LLM_ESTIMATED } },
        )
    }

    /**
     * 캐시가 없거나 `generatedAt`이 그날 `Meal`의 최종 `updatedAt`보다 이르면 무효다. 이때
     * **LLM을 부르지 않고** `feedback = null`인 마커 행을 먼저 upsert한다 — "생성이 이미 걸렸다"는
     * 표시라, 사용자가 폴링하며 여러 번 조회해도 뒤에서 나가는 호출은 한 번뿐이다. 이 표시가 없으면
     * 폴링이 곧 무제한 호출이 된다. 마커를 커밋한 뒤에 `runAfterCommit`으로 실제 생성을 트리거한다
     * — 커밋 전에 출발하면 비동기 스레드가 방금 쓴 행을 못 본다.
     *
     * **키가 없으면 마커조차 남기지 않는다.** `MealAnalysisService.retry`·`MealService.retryFeedback`과
     * 같은 계열의 가드인데, 여기만 빠져 있었다. 마커를 남기면 `generateForDay`가 즉시 반환한 뒤에도
     * `generatedAt`이 끼니 `updatedAt`보다 뒤라 **유효한 캐시로 굳는다.** 나중에 키를 넣어도 그 판정은
     * 그대로여서 그날 피드백은 영영 안 만들어지고, 하루 피드백에는 재시도 엔드포인트가 없어
     * 빠져나갈 길도 없다(끼니를 고쳐 `updatedAt`을 올리는 우회뿐이다).
     *
     * 생성이 **실패**했을 때 마커를 남겨 두는 것은 이와 달리 의도된 설계다(`DietFeedbackGenerator`
     * 주석 — 자동 재시도를 넣지 않는다). 실패는 일시적이지만 키 없음은 설정을 바꿔도 안 풀린다.
     */
    private fun resolveFeedback(
        user: User,
        date: LocalDate,
        meals: List<Meal>,
        dayScore: Int,
    ): String? {
        val cached = feedbackRepository.findByUserAndDate(user, date)
        // **`updatedAt`이 아니라 `contentUpdatedAt`이다.** 끼니 피드백이 실리면 `updatedAt`이
        // 오르는데 하루 프롬프트는 그 값을 읽지도 않아서, 그대로 두면 확정 한 번마다 이미 실린
        // 문장을 지우고 유료 호출을 한 번 더 걸었다(`Meal.contentUpdatedAt` 주석).
        val latestMealUpdate = meals.maxOf { it.contentUpdatedAt }
        if (cached != null && !cached.generatedAt.isBefore(latestMealUpdate)) return cached.feedback

        // **캐시 적중 검사보다 뒤에 둔다.** 앞에 두면 키가 없는 동안 이미 만들어 둔 피드백까지
        // 가려진다 — 막으려는 건 「생성할 수 없으면서 생성했다는 표시를 남기는 것」뿐이다.
        if (!feedbackGenerator.isAvailable) return null

        val now = LocalDateTime.now()
        if (cached == null) {
            feedbackRepository.save(
                DailyDietFeedback(user = user, date = date, dayScore = dayScore, feedback = null, generatedAt = now),
            )
        } else {
            cached.update(dayScore, null, now)
        }
        // 마커 시각을 함께 넘긴다 — 생성이 끝났을 때 그 사이 새 마커가 찍혔는지 대조한다.
        runAfterCommit { feedbackGenerator.generateForDay(user.requiredId, date, now) }
        return null
    }

    private fun findUser(username: String): User =
        userRepository.findByUsername(username)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)
}
