package com.toy.backend.diet.chat

import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.DietErrorCode
import com.toy.backend.diet.llm.ChatTurn
import com.toy.backend.diet.llm.OpenRouterClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.LocalDate

/**
 * **트랜잭션을 걸지 않는다.** LLM 호출이 `DietChatStore`의 두 트랜잭션 사이, 트랜잭션 **밖**에서
 * 일어나야 한다(함정 1).
 */
@Service
class DietChatService(
    private val store: DietChatStore,
    // 키가 없으면 빈이 등록되지 않는다 — `DietFeedbackGenerator`와 같은 모양이다.
    @Autowired(required = false) private val client: OpenRouterClient?,
) {
    val isAvailable: Boolean get() = client != null

    /**
     * **가드가 맨 앞이다**(함정 4). 키가 없으면 컨텍스트를 읽지도 않고 거절한다 — 읽어 봐야
     * 호출할 수 없고, 상한 검사에 걸려 엉뚱한 오류가 나갈 수도 있다.
     */
    fun ask(
        username: String,
        date: LocalDate,
        message: String,
    ): DietChatAnswerResponse {
        val openRouter = client ?: throw CustomException(DietErrorCode.LLM_UNAVAILABLE)
        val context = store.loadContext(username, date)
        // 데이터 → 총평 → 히스토리 → 이번 질문. 총평은 대화의 출발점이라 assistant 자리에 넣고,
        // 아직 생성 전이면 그 줄을 뺀다(넣으면 빈 assistant 턴이 되어 히스토리가 어긋난다).
        val turns =
            buildList {
                add(ChatTurn("user", context.dataBlock))
                context.dayFeedback?.let { add(ChatTurn("assistant", it)) }
                addAll(context.history)
                add(ChatTurn("user", message))
            }
        // 답을 못 받으면 두 행 다 저장하지 않는다(함정 3) — 질문만 남으면 히스토리가
        // user, user, assistant로 어긋나 다음 턴 프롬프트가 깨진다. 사용자는 다시 물으면 된다.
        val answer = openRouter.chat(DietChatPrompts.SYSTEM_PROMPT, turns) ?: throw CustomException(DietErrorCode.CHAT_FAILED)
        return store.append(username, date, message, answer)
    }

    /** **키가 없어도 동작한다**(함정 4) — 저장된 대화를 보여주는 데는 LLM이 필요 없다. */
    fun history(
        username: String,
        date: LocalDate,
    ): DietChatResponse = store.history(username, date)
}
