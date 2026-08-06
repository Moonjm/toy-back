package com.toy.backend.diet.chat

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

/** `@Size(max = 500)`은 프롬프트가 통째로 커지는 것을 막는 상한이다. */
data class DietChatRequest(
    @field:NotBlank @field:Size(max = 500)
    val message: String,
)

data class DietChatMessageResponse(
    val id: Long,
    val role: ChatRole,
    val content: String,
    val createdAt: LocalDateTime,
)

/**
 * `remainingTurns`를 함께 내려야 앱이 상한에 닿았을 때 입력창을 잠글 수 있다 — 없으면 앱이
 * 메시지를 세어 서버 상한을 추측하게 된다.
 */
data class DietChatAnswerResponse(
    val message: DietChatMessageResponse,
    val remainingTurns: Int,
)

data class DietChatResponse(
    val messages: List<DietChatMessageResponse>,
    val remainingTurns: Int,
)
