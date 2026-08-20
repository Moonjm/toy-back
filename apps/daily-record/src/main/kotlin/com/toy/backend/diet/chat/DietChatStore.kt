package com.toy.backend.diet.chat

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.daily.DailyActivityRepository
import com.toy.backend.diet.daily.NutrientLimitEvaluator
import com.toy.backend.diet.daily.NutrientStatus
import com.toy.backend.diet.feedback.DailyDietFeedback
import com.toy.backend.diet.feedback.DailyDietFeedbackRepository
import com.toy.backend.diet.feedback.totals
import com.toy.backend.diet.llm.ChatTurn
import com.toy.backend.diet.meal.Meal
import com.toy.backend.diet.meal.MealRepository
import com.toy.backend.diet.score.DietScoreCalculator
import com.toy.backend.file.FileService
import com.toy.backend.user.User
import com.toy.backend.user.UserRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.roundToInt

private val log = KotlinLogging.logger {}

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
    private val fileService: FileService,
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
                .findByUserAndTypeAndCreatedAtAfterOrderByIdDesc(
                    user,
                    ChatMessageType.TEXT,
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
            // 방금 저장한 TEXT 행이라 매달린 참조가 있을 수 없다.
            ?: error("방금 저장한 TEXT 답이 카드 변환에서 사라졌다 — toResponse가 TEXT를 버리고 있다")
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
        val user = findUser(username)
        val rows =
            messageRepository.findByUserAndIdLessThanOrderByIdDesc(
                user,
                // 첫 장은 커서가 없다 — 가장 큰 id보다 큰 값으로 열어 준다.
                before ?: Long.MAX_VALUE,
                PageRequest.of(0, size + 1),
            )
        val page = rows.take(size)
        val mealCards = mealCardsOf(user, page)
        val dayCards = dayCardsOf(user, page)
        return DietChatPageResponse(
            // **매달린 참조는 행째로 뺀다.** 삭제 경로가 제대로 돌면 생기지 않지만, 생겼을 때
            // 빈 카드를 내리는 것보다 낫다. `nextCursor`는 원래 행에서 내므로 흔들리지 않는다.
            messages = page.mapNotNull { it.toResponse(mealCards, dayCards) },
            nextCursor = if (rows.size > size) page.last().requiredId else null,
        )
    }

    /**
     * 카드가 가리키는 끼니를 **`IN` 한 번**으로 읽어 `mealId`별 카드로 만든다. 한 장이 100건까지
     * 오므로 카드마다 조회하면 그대로 N+1이다. presigned URL도 한 번에 받는다.
     *
     * **조회를 [user]로 조인다.** `findAllById`로도 같은 일을 하지만 그것은 남의 끼니도
     * 돌려준다. 여기 들어오는 id는 카드의 `meal_id`인데 그 컬럼에는 FK도 소유권 제약도 없어
     * (`DietChatMessage.mealId` 주석), 카드를 쓰는 경로가 한 번만 새면 **남의 영양 수치와
     * presigned 사진 URL이 그대로 나간다.** 지금 그 경로는 없지만 받치는 것도 여기뿐이다 —
     * `dayCardsOf`가 이미 같은 이유로 사용자별 조회를 쓴다.
     */
    private fun mealCardsOf(
        user: User,
        page: List<DietChatMessage>,
    ): Map<Long, ChatMealCard> {
        val ids = page.filter { it.type == ChatMessageType.MEAL_CARD }.mapNotNull { it.mealId }
        if (ids.isEmpty()) return emptyMap()
        val meals = mealRepository.findByUserAndIdIn(user, ids)
        val urls = fileService.getPresignedUrls(meals.mapNotNull { it.photos.firstOrNull()?.fileId })
        return meals.associate { it.requiredId to it.toChatCard(urls) }
    }

    /** `@OrderBy("sortOrder asc")`라 `photos.first()`가 첫 장이다. */
    private fun Meal.toChatCard(urls: Map<Long, String>): ChatMealCard {
        // 점수와 근거를 한 번에 받는다 — 따로 구하면 둘이 어긋난다(`MealDtos.toResponse`와 같다).
        val scored = DietScoreCalculator.scoreMeal(carbsG, proteinG, fatG)
        return ChatMealCard(
            mealId = requiredId,
            mealType = mealType,
            score = scored.score,
            scoreBasis = scored.basis,
            totalKcal = totalKcal,
            carbsG = carbsG,
            proteinG = proteinG,
            fatG = fatG,
            photoUrl = photos.firstOrNull()?.let { urls[it.fileId] },
            feedback = feedback,
        )
    }

    /**
     * 총평 카드가 가리키는 날짜들을 **`IN` 한 번**으로 읽어 날짜별 카드로 만든다.
     *
     * **카드는 끼니로 만들고, 캐시 행(`DailyDietFeedback`)은 문장 하나만 얹는다.** 캐시 행을
     * 기준으로 만들면 `MealService.delete`가 그날 끼니 아무거나 하나만 지워도 캐시 행을
     * 통째로 지우므로(`dailyFeedbackRepository.deleteByUserAndDate`), 두 끼니가 남아 있는데도
     * 카드가 통째로 사라진다. 그날 끼니가 **하나도** 없어야 진짜로 빈 날이다.
     */
    private fun dayCardsOf(
        user: User,
        page: List<DietChatMessage>,
    ): Map<LocalDate, ChatDayCard> {
        val dates = page.filter { it.type == ChatMessageType.DAY_SUMMARY }.map { it.date }.distinct()
        if (dates.isEmpty()) return emptyMap()
        // 그날 끼니는 총평의 열량·점수·목표를 내는 데 필요하다. 목표는 **첫 끼니의 스냅샷**이라
        // 프로필을 읽지 않는다 — 읽으면 몸무게를 바꿨을 때 과거 카드의 분모가 흔들린다.
        // `OrderByCreatedAtAscIdAsc`로 읽어야 날짜별로 묶었을 때 `first()`가 실제 첫 끼니가
        // 된다(Kotlin `groupBy`는 원래 순서를 보존한다).
        val mealsByDate = mealRepository.findByUserAndDateInOrderByCreatedAtAscIdAsc(user, dates).groupBy { it.date }
        val feedbackByDate = feedbackRepository.findByUserAndDateIn(user, dates).associateBy { it.date }
        return dates
            .mapNotNull { date ->
                val meals = mealsByDate[date]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val totals = meals.totals()
                val targets = meals.first().targets()
                date to
                    ChatDayCard(
                        // 저장된 컬럼이 아니라 현재 끼니에서 재계산한다 — `ChatMealCard.score`와
                        // 같은 이유다. 캐시 행의 `dayScore`는 그 문장을 만들 때의 스냅샷이라
                        // 끼니를 고친 뒤에는 지금 합계와 어긋난다.
                        dayScore = DietScoreCalculator.scoreDay(totals.kcal, totals.carbsG, totals.proteinG, totals.fatG, targets).score,
                        totalKcal = totals.kcal,
                        targetKcal = meals.first().targetKcal,
                        // 캐시가 없거나 낡았으면(`isFreshFor`) null이다 — 카드는 남고
                        // 앱이 「만들고 있어요」를 띄운다.
                        feedback = feedbackByDate[date]?.takeIf { it.isFreshFor(meals) }?.feedback,
                    )
            }.toMap()
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
    ): String? =
        feedbackRepository
            .findByUserAndDate(user, date)
            ?.takeIf { it.isFreshFor(meals) }
            ?.feedback

    /**
     * 캐시 무효화 판정. `dayCardsOf`도 같은 기준을 쓴다 — 두 곳이 각자 계산하면 한쪽만
     * 고쳐지는 자리가 생긴다.
     */
    private fun DailyDietFeedback.isFreshFor(meals: List<Meal>): Boolean = !generatedAt.isBefore(meals.maxOf { it.contentUpdatedAt })

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

    /**
     * 카드 자리는 `mealCards`가 채운다. **매달린 참조면 null을 돌려주고 로그를 남긴다** —
     * 삭제 경로(`MealService.delete`·`mergeInto`)가 새고 있다는 신호라, 조용히 넘기면 아무도 모른다.
     */
    private fun DietChatMessage.toResponse(
        mealCards: Map<Long, ChatMealCard> = emptyMap(),
        dayCards: Map<LocalDate, ChatDayCard> = emptyMap(),
    ): DietChatMessageResponse? {
        val meal = if (type == ChatMessageType.MEAL_CARD) mealCards[mealId] else null
        if (type == ChatMessageType.MEAL_CARD && meal == null) {
            log.warn { "카드가 가리키는 끼니가 없어 건너뛴다 — 삭제 경로가 새고 있다: messageId=$requiredId, mealId=$mealId" }
            return null
        }
        val day = if (type == ChatMessageType.DAY_SUMMARY) dayCards[date] else null
        // `dayCardsOf`는 끼니가 하나라도 남아 있으면 카드를 만든다 — 끼니 하나를 지워도
        // 캐시 행이 통째로 지워지는 것과 무관하다(`MealService.delete`). 여기서 null인 것은
        // 그날 끼니가 하나도 안 남은, 진짜로 빈 날뿐이다 — 정상 경로라 끼니 카드와 달리
        // 로그를 남기지 않는다.
        if (type == ChatMessageType.DAY_SUMMARY && day == null) return null
        return DietChatMessageResponse(
            id = requiredId,
            type = type,
            date = date,
            role = role,
            createdAt = createdAt,
            content = if (type == ChatMessageType.TEXT) content else null,
            meal = meal,
            day = day,
        )
    }
}
