package com.example.backend.pair.event

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.LocalDate

@Schema(description = "페어 이벤트 응답")
data class PairEventResponse(
    @field:Schema(description = "이벤트 ID", example = "1")
    val id: Long,
    @field:Schema(description = "제목", example = "결혼기념일")
    val title: String,
    @field:Schema(description = "이모지", example = "💍")
    val emoji: String,
    @field:Schema(description = "이벤트 날짜", example = "2026-03-14")
    val eventDate: String,
    @field:Schema(description = "매년 반복 여부")
    val recurring: Boolean,
)

@Schema(description = "페어 이벤트 등록 요청")
data class PairEventRequest(
    @field:Schema(description = "제목", example = "결혼기념일")
    @field:NotBlank
    @field:Size(max = 30)
    val title: String,
    @field:Schema(description = "이모지", example = "💍")
    @field:NotBlank
    @field:Size(max = 10)
    val emoji: String,
    @field:Schema(description = "이벤트 날짜", example = "2026-03-14")
    @field:NotNull
    val eventDate: LocalDate,
    @field:Schema(description = "매년 반복 여부", example = "true")
    val recurring: Boolean = true,
)

fun PairEvent.toResponse(): PairEventResponse =
    PairEventResponse(
        id = requiredId,
        title = title,
        emoji = emoji,
        eventDate = eventDate.toString(),
        recurring = recurring,
    )
