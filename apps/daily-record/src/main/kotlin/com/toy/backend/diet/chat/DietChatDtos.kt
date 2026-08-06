package com.toy.backend.diet.chat

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
    /**
     * **어느 날에 대한 질문인가.** `createdAt`(언제 물었나)과 다르다 — 8월 6일에 8월 1일을
     * 물을 수 있다. 스트림이 물은 시각 순이라 그 질문이 8월 6일 대화 사이에 앉으므로,
     * 앱이 말풍선에 「8/1에 대해」를 붙이려면 이 값이 필요하다.
     */
    val date: LocalDate,
    val role: ChatRole,
    val content: String,
    val createdAt: LocalDateTime,
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
