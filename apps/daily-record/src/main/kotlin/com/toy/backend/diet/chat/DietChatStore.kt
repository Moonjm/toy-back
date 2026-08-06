package com.toy.backend.diet.chat

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.daily.DailyActivityRepository
import com.toy.backend.diet.daily.NutrientLimitEvaluator
import com.toy.backend.diet.daily.NutrientStatus
import com.toy.backend.diet.feedback.DailyDietFeedbackRepository
import com.toy.backend.diet.feedback.totals
import com.toy.backend.diet.llm.ChatTurn
import com.toy.backend.diet.meal.Meal
import com.toy.backend.diet.meal.MealRepository
import com.toy.backend.diet.score.DietScoreCalculator
import com.toy.backend.user.User
import com.toy.backend.user.UserRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.roundToInt

/**
 * 프롬프트 재료와 히스토리. **엔티티를 담지 않는다** — 트랜잭션 밖으로 나가는 값이다.
 */
data class ChatContext(
    /** `DietChatPrompts.context(...)` 결과. 매 요청 새로 만들고 저장하지 않는다(함정 2). */
    val dataBlock: String,
    /** 저장된 하루 피드백. 아직 생성 전이면 null이고, 그때는 오프닝 턴을 뺀다. */
    val dayFeedback: String?,
    val history: List<ChatTurn>,
)

/**
 * 채팅의 DB 왕복을 **각각 짧은 트랜잭션**으로 끊는다. `MealFeedbackStore`·`DayFeedbackStore`와
 * 같은 이유이고 같은 모양이다 — LLM 호출을 트랜잭션 안에서 하면 엔티티가 호출 내내 영속성
 * 컨텍스트에 남고, 그 사이 항목이 수정되면 커밋의 dirty check가 합계 컬럼을 옛 값으로 되돌린다.
 * **채팅은 그 창이 더 넓다** — 대화가 쌓일수록 호출이 길어지고 사용자가 그동안 화면을 보고 있다.
 */
@Component
class DietChatStore(
    private val userRepository: UserRepository,
    private val mealRepository: MealRepository,
    private val activityRepository: DailyActivityRepository,
    private val feedbackRepository: DailyDietFeedbackRepository,
    private val messageRepository: DietChatMessageRepository,
) {
    /**
     * **조회를 한 번으로 묶는다.** 기준일과 직전 7일을 따로 읽지 않고 8일치를 받아 날짜로 쪼갠다.
     * 이 정렬(`createdAt asc`)은 그날 첫 끼니의 스냅샷을 목표로 쓰기 위한 것이라 이 용도에 맞는다.
     */
    @Transactional(readOnly = true)
    fun loadContext(
        username: String,
        date: LocalDate,
    ): ChatContext {
        val user = findUser(username)
        val window = DietChatPrompts.RECENT_DAYS.toLong()
        val byDate =
            mealRepository
                .findByUserAndDateBetweenOrderByDateAscCreatedAtAscIdAsc(user, date.minusDays(window), date)
                .groupBy { it.date }

        // 기준일이 비면 직전 7일이 있어도 거절한다 — 화면이 기준일 요약으로 시작하는 구조라
        // 보여줄 것이 없고, 기록이 있는 날을 열면 되는 일이다. 앱이 먼저 막고 여기는 안전망이다.
        val meals = byDate[date] ?: throw CustomException(ErrorCode.INVALID_REQUEST, "그날 기록된 끼니가 없습니다")

        val totals = meals.totals()
        val targets = meals.first().targets()
        val dayScore = DietScoreCalculator.scoreDay(totals.kcal, totals.carbsG, totals.proteinG, totals.fatG, targets).score
        val recent = (window downTo 1).map { back -> summarize(date.minusDays(back), byDate) }

        // **히스토리는 큐다.** 7일 이내에 물은 것 중 최근 20턴만 싣고 오래된 것은 밀려난다 —
        // 막지 않는다. 그래서 대화가 100번 쌓여도 요청 크기가 유계다.
        //
        // `id DESC`로 받아 뒤집는다. `asc`로 받으면 **가장 오래된** 20턴이 되어 정반대가 된다.
        // `append`가 질문·답을 한 트랜잭션에 함께 쓰므로 행은 늘 교대하고, 짝수 개를 가져오니
        // 뒤집은 목록의 맨 앞은 항상 질문이다.
        val history =
            messageRepository
                .findByUserAndCreatedAtAfterOrderByIdDesc(
                    user,
                    LocalDateTime.now().minusDays(DietChatPrompts.HISTORY_DAYS),
                    PageRequest.of(0, DietChatPrompts.HISTORY_TURNS * 2),
                ).reversed()

        return ChatContext(
            dataBlock =
                DietChatPrompts.context(
                    date,
                    meals,
                    totals,
                    targets,
                    dayScore,
                    activityRepository.findByUserAndDate(user, date)?.activeEnergyKcal,
                    recent,
                ),
            dayFeedback = freshFeedback(user, date, meals),
            history = DietChatPrompts.historyTurns(history),
        )
    }

    /** 질문·답 두 행을 순서대로 저장하고 **저장된 답 한 건**을 돌려준다. id·`createdAt`이 이 트랜잭션 안에서 채워진다. */
    @Transactional
    fun append(
        username: String,
        date: LocalDate,
        question: String,
        answer: String,
    ): DietChatMessageResponse {
        val user = findUser(username)
        messageRepository.save(DietChatMessage(user, date, ChatRole.USER, question))
        return messageRepository.save(DietChatMessage(user, date, ChatRole.ASSISTANT, answer)).toResponse()
    }

    /**
     * 화면용 페이징. **키가 없어도 동작한다** — 저장된 대화를 보여주는 데는 LLM이 필요 없다(함정 4).
     *
     * **한 건 더 받아 「다음 장이 있는가」를 판별한다.** `size`만 받으면 마지막 장이 정확히
     * 꽉 찼을 때 커서가 남아 앱이 빈 요청을 한 번 더 한다.
     */
    @Transactional(readOnly = true)
    fun page(
        username: String,
        before: Long?,
        size: Int,
    ): DietChatPageResponse {
        val rows =
            messageRepository.findByUserAndIdLessThanOrderByIdDesc(
                findUser(username),
                // 첫 장은 커서가 없다 — 가장 큰 id보다 큰 값으로 열어 준다.
                before ?: Long.MAX_VALUE,
                PageRequest.of(0, size + 1),
            )
        val page = rows.take(size)
        return DietChatPageResponse(
            messages = page.map { it.toResponse() },
            nextCursor = if (rows.size > size) page.last().requiredId else null,
        )
    }

    /**
     * 저장된 하루 총평은 **캐시**라 낡을 수 있다. 무효 판정은 `DailyDietService.resolveFeedback`과
     * 같은 기준이다 — `generatedAt`이 그날 끼니의 최종 `contentUpdatedAt`보다 이르면 낡았다.
     *
     * **낡았으면 재생성하지 않고 뺀다.** 여기는 `readOnly` 트랜잭션이고, 채팅이 유료 호출을
     * 촉발해서는 안 된다. 총평 없이 시작하는 길은 이미 있다(아직 생성 전이면 null이다).
     *
     * 빼지 않으면 **수정 전 음식·합계를 말하는 문장이 최신 데이터 블록과 한 프롬프트에 섞여**,
     * 모델이 지금은 없는 음식을 근거로 답한다. 하루 화면을 다시 열면 마커 upsert가 이 문장을
     * null로 밀지만(`DailyDietService`), 과거 날짜 채팅은 그 화면을 거치지 않는다.
     */
    private fun freshFeedback(
        user: User,
        date: LocalDate,
        meals: List<Meal>,
    ): String? {
        val latestMealUpdate = meals.maxOf { it.contentUpdatedAt }
        return feedbackRepository
            .findByUserAndDate(user, date)
            ?.takeIf { !it.generatedAt.isBefore(latestMealUpdate) }
            ?.feedback
    }

    /** 기록이 없는 날도 자리를 만든다 — 빼면 모델이 날짜가 연속인 줄 알고 없는 추세를 만든다. */
    private fun summarize(
        day: LocalDate,
        byDate: Map<LocalDate, List<Meal>>,
    ): RecentDaySummary {
        val meals = byDate[day]
        if (meals.isNullOrEmpty()) {
            return RecentDaySummary(day, null, 0.0, emptyList(), emptyList())
        }
        val totals = meals.totals()
        val targets = meals.first().targets()
        return RecentDaySummary(
            date = day,
            dayScore = DietScoreCalculator.scoreDay(totals.kcal, totals.carbsG, totals.proteinG, totals.fatG, targets).score,
            totalKcal = totals.kcal,
            // 방향 단어를 새로 만들지 않고 기준 문구를 그대로 싣는다 — 「이하」·「이상」이 들어 있어
            // 모델이 방향을 읽고, 기준값이 함께 가서 많은지 적은지 스스로 판단하지 않는다.
            warnings =
                NutrientLimitEvaluator
                    .evaluate(totals, targets)
                    .filter { it.status == NutrientStatus.WARN }
                    .map { "${it.name} ${it.intake.roundToInt()}${it.unit}(기준 ${it.standardText})" },
            meals = meals.map { it.mealType to it.items.map { item -> item.foodName } },
        )
    }

    private fun findUser(username: String): User =
        userRepository.findByUsername(username)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)

    private fun DietChatMessage.toResponse() = DietChatMessageResponse(requiredId, date, role, content, createdAt)
}
