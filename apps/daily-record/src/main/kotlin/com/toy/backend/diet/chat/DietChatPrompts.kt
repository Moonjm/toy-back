package com.toy.backend.diet.chat

import com.toy.backend.diet.feedback.DietFeedbackPrompts
import com.toy.backend.diet.feedback.NutritionTotals
import com.toy.backend.diet.llm.ChatTurn
import com.toy.backend.diet.meal.Meal
import com.toy.backend.diet.profile.NutritionTargets
import com.toy.backend.diet.score.DietScoreCalculator
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

object DietChatPrompts {
    /** 컨텍스트에 싣는 과거 창. 「이번 주」 감각과 맞춘 값이다. */
    const val RECENT_DAYS = 7

    /** 프롬프트에 싣는 대화 창. 넘으면 오래된 턴부터 밀려난다 — 막지 않는다. */
    const val HISTORY_TURNS = 20

    /** 히스토리로 거슬러 올라가는 기간. **`createdAt` 기준**이라 `RECENT_DAYS`와 축이 다르다. */
    const val HISTORY_DAYS = 7L

    /**
     * **범위 제한이 피드백보다 중요하다** — 피드백은 우리가 주제를 정하지만 채팅은 사용자가
     * 정한다. 「이 약 먹어도 돼?」·「살 빼는 법」이 실제로 온다.
     *
     * 막는 것은 둘뿐이다 — 직전 7일보다 먼 날짜와, 7일 안이지만 요약보다 자세한 것.
     * **둘 다 거절이 아니라 길 안내로 돌린다.** 대화가 날짜별로 열려 있어서 그렇게 하면
     * 실제로 답을 얻는다.
     */
    const val SYSTEM_PROMPT =
        "당신은 식단 코치입니다. 사용자의 식단 기록에 대해 묻는 말에 한국어 존댓말로 답하세요.\n" +
            "앞서 드린 끼니 기록·[끼니별 상세]·[직전 7일]에 있는 사실만 근거로 삼으세요. 기록에 " +
            "없는 것은 추측하지 말고 모른다고 말하세요 — 숫자를 지어내면 안 됩니다.\n" +
            "3문장 이내로 짧게. 목록 기호는 쓰지 마세요.\n" +
            "금지: 의학적 진단·처방, 특정 질환 언급, 영양제 권유.\n" +
            "식단·영양과 무관한 질문에는 답하지 말고, 식단에 대한 질문을 받겠다고 안내하세요.\n" +
            // 실제로 실리는 것과 어긋나면 모델이 눈앞의 주의 줄을 두고도 「그 정보는 없습니다」라고
            // 답한다. `recentDaysBlock`이 무엇을 찍는지와 이 문장은 함께 움직여야 한다.
            "[직전 7일]에는 그날의 점수·열량, 주의 영양소, 먹은 음식 이름만 있습니다. 그보다 " +
            "자세한 것을 물으면 그 날짜를 열어서 물어봐 달라고 안내하세요.\n" +
            "[직전 7일]보다 먼 날짜는 볼 수 없습니다. 그때도 그 날짜를 열어서 물어봐 달라고 하세요."

    /**
     * 기준일 상세 + 직전 7일. **기준일이 먼저다** — 대화의 주제는 기준일이고 7일은 배경이며,
     * 시스템 프롬프트가 「앞서 드린」으로 가리키는 순서와도 맞는다.
     *
     * **매 요청 새로 만들고 저장하지 않는다**(함정 2).
     */
    fun context(
        date: LocalDate,
        meals: List<Meal>,
        totals: NutritionTotals,
        targets: NutritionTargets,
        dayScore: Int,
        activeEnergyKcal: Int?,
        recentDays: List<RecentDaySummary>,
    ): String =
        buildString {
            append(DietFeedbackPrompts.day(date, meals, totals, targets, dayScore, activeEnergyKcal))
            appendLine()
            appendLine("[끼니별 상세]")
            // 화면이 「점심 47점」을 보여주므로 「왜 그래?」가 반드시 온다. day()는 이름과 열량만
            // 담아 그 질문에 못 답한다. basis는 저장하지 않고 그때 다시 계산한다 — 감점 기울기를
            // 바꿨을 때 응답과 프롬프트가 어긋나지 않는다.
            //
            // meal()은 끼니 피드백 생성에도 쓰여 첫 줄이 "[이번 끼니] X"다 — 여기서는 여러 끼니가
            // 목록으로 나열되니 그 라벨이 어느 것도 가리키지 못한다("이번"이 모호). meal() 자체는
            // 건드리지 않고(끼니 피드백 프롬프트가 함께 바뀌므로) 이 자리에서만 "[끼니 상세] X"로
            // 바꿔 붙이고, 블록 사이에 빈 줄을 넣어 경계를 분명히 한다 — 안 그러면 모델이 마지막
            // 블록만 「이번 끼니」로 붙들어 점수를 엉뚱한 끼니에 귀속시킬 수 있다.
            meals.forEach {
                val block =
                    DietFeedbackPrompts
                        .meal(it, DietScoreCalculator.scoreMeal(it.carbsG, it.proteinG, it.fatG).basis)
                        .replaceFirst("[이번 끼니] ", "[끼니 상세] ")
                append(block)
                appendLine()
            }
            append(recentDaysBlock(recentDays))
        }

    /**
     * 직전 7일. 하루 한 줄 + 끼니별 **음식 이름까지만**.
     *
     * **기록 없는 날도 줄을 남긴다.** 빼 버리면 모델이 날짜가 연속인 줄 알고 「사흘 연속
     * 좋았다」처럼 없는 추세를 만든다.
     */
    fun recentDaysBlock(days: List<RecentDaySummary>): String =
        buildString {
            appendLine("[직전 ${days.size}일]")
            days.forEach { day ->
                if (day.dayScore == null) {
                    appendLine("- ${day.date.format(RECENT_DATE)} 기록 없음")
                    return@forEach
                }
                append("- ${day.date.format(RECENT_DATE)} ${day.dayScore}점 ${day.totalKcal.roundToInt()}kcal")
                if (day.warnings.isNotEmpty()) append(" · 주의: ${day.warnings.joinToString(", ")}")
                appendLine()
                day.meals.forEach { (type, foods) -> appendLine("    ${type.label}: ${foods.joinToString(", ")}") }
            }
        }

    /**
     * 히스토리를 API 턴으로 바꾼다. **사용자 턴 앞에 그 질문의 날짜를 붙인다**(함정 5-1) —
     * 히스토리가 날짜를 넘나들어서, 안 붙이면 8월 3일에 물은 「점심 왜 낮아?」를 모델이
     * 오늘 점심 얘기로 읽는다.
     *
     * 붙이는 값은 `date`(어느 날에 대한 질문인가)이지 `createdAt`(언제 물었나)이 아니다 —
     * 8월 6일에 8월 1일을 물었다면 `[08-01]`이다.
     *
     * **답변에는 안 붙인다.** 바로 뒤에 와서 짝이 명확하고, 양쪽에 붙이면 노이즈만 는다.
     */
    fun historyTurns(messages: List<DietChatMessage>): List<ChatTurn> =
        messages.map {
            when (it.role) {
                ChatRole.USER -> ChatTurn("user", "[${it.date.format(HISTORY_DATE)}] ${it.content}")
                ChatRole.ASSISTANT -> ChatTurn("assistant", it.content)
            }
        }

    /** `07-30 (목)`. 기준일 헤더와 달리 연도를 빼 줄을 짧게 유지한다 — 같은 해 안의 최근 7일이다. */
    private val RECENT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd (E)", Locale.KOREAN)

    /**
     * 히스토리 접두. 연도를 빼 줄을 짧게 유지한다 — 찍히는 값은 `date`(어느 날에 대한
     * 질문인가)이고, `date`는 7일 창에 갇혀 있지 않다(`POST`가 상한 없이 과거 날짜를 받는다).
     * 그래도 빼는 이유는 같은 MM-dd가 해를 걸쳐 겹칠 확률이 낮아서다.
     */
    private val HISTORY_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")
}
