package com.toy.backend.study

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "세션 시작 요청")
data class StudySessionStartRequest(
    @field:Schema(description = "과목", example = "FISCAL")
    val subject: StudySubject,
)

@Schema(description = "세션 응답")
data class StudySessionResponse(
    val id: Long,
    val subject: StudySubjectResponse,
    val startedAt: LocalDateTime,
    val endedAt: LocalDateTime?,
    val totalSeconds: Long,
    val pauses: List<StudyPauseResponse>,
)

@Schema(description = "일시정지 응답")
data class StudyPauseResponse(
    val id: Long,
    val pausedAt: LocalDateTime,
    val resumedAt: LocalDateTime?,
)

fun StudySession.toResponse(): StudySessionResponse =
    StudySessionResponse(
        id = requiredId,
        subject = subject.toResponse(),
        startedAt = startedAt,
        endedAt = endedAt,
        totalSeconds = totalSeconds,
        pauses = pauses.map { it.toResponse() },
    )

fun StudyPause.toResponse(): StudyPauseResponse =
    StudyPauseResponse(
        id = requiredId,
        pausedAt = pausedAt,
        resumedAt = resumedAt,
    )
