package com.toy.backend.diet.chat

import com.toy.backend.diet.meal.MealType
import com.toy.backend.diet.score.MealScoreBasis
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDate
import java.time.LocalDateTime

/** `@Size(max = 500)`은 프롬프트가 통째로 커지는 것을 막는 상한이다. */
data class DietChatRequest(
    @field:NotBlank @field:Size(max = 500)
    val message: String,
)

data class DietChatMessageResponse(
    val id: Long,
    /** 무엇이 놓인 자리인가. 앱이 이 값으로 렌더링을 가른다. */
    val type: ChatMessageType,
    /**
     * **어느 날에 대한 것인가.** `createdAt`(언제 있었나)과 다르다 — 8월 6일에 8월 1일을
     * 물을 수 있고, 8월 1일 끼니를 8월 3일에 뒤늦게 확정할 수도 있다. 스트림이 시각 순이라
     * 그것들이 다른 날 사이에 앉으므로, 앱이 「8/1에 대해」를 붙이려면 이 값이 필요하다.
     */
    val date: LocalDate,
    val role: ChatRole,
    val createdAt: LocalDateTime,
    /**
     * `TEXT`일 때만 있다. **카드 행은 null이다** — DB에는 빈 문자열로 저장되지만 그대로
     * 내보내면 앱이 빈 말풍선을 그릴 여지가 생긴다. 「없다」와 「비어 있다」를 같은 값으로
     * 만들지 않는다.
     */
    val content: String?,
    /** `MEAL_CARD`일 때만. */
    val meal: ChatMealCard? = null,
    /** `DAY_SUMMARY`일 때만. */
    val day: ChatDayCard? = null,
)

/**
 * 끼니 카드. **저장된 값이 아니라 조회 시점의 끼니에서 읽은 값이다** — 스냅샷으로 담으면
 * 끼니를 고칠 때마다 낡는다.
 */
data class ChatMealCard(
    val mealId: Long,
    val mealType: MealType,
    /** 저장된 `Meal.score` 컬럼이 아니라 재계산 값이다 — 화면·프롬프트와 같아야 한다. */
    val score: Int?,
    /**
     * [score]와 **같은 계산에서 나온다.** 앱이 탄단지 구성비 막대를 그리는데, 비율의 분모가
     * `totalKcal`이 아니라 매크로에서 역산한 값이라(`DietScoreCalculator` 주석) 앱이 g에서
     * 직접 나누면 다른 숫자가 나온다. `MealResponse.scoreBasis`와 같은 타입이다.
     */
    val scoreBasis: MealScoreBasis?,
    val totalKcal: Double,
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double,
    /** 사진 한 장. 없으면 null. presigned URL이라 매 조회 새로 발급된다. */
    val photoUrl: String?,
    /** 생성 중이거나 실패했으면 null — 앱이 그 자리를 로딩으로 채운다. */
    val feedback: String?,
)

/** 총평 카드. 끼니 카드와 같은 이유로 조회 시점 값이다. */
data class ChatDayCard(
    val dayScore: Int,
    val totalKcal: Double,
    /**
     * 그날 **첫 끼니의 스냅샷**(`Meal.targetKcal`)이다. 프로필의 현재 목표를 읽으면 몸무게를
     * 바꿨을 때 과거 카드의 분모가 함께 흔들린다.
     */
    val targetKcal: Int,
    /** 재생성 중이면 null — 앱이 「마감 피드백을 만들고 있어요」를 띄운다. */
    val feedback: String?,
)

/**
 * 한 장. **`nextCursor`가 null이면 더 없다** — 앱이 그때 무한 스크롤을 멈춘다.
 *
 * 최신이 먼저 온다(`id DESC`). 앱이 뒤집어 아래에 붙인다.
 */
data class DietChatPageResponse(
    val messages: List<DietChatMessageResponse>,
    val nextCursor: Long?,
)
