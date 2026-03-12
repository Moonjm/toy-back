package com.toy.backend.study

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import java.time.LocalDate
import java.time.LocalDateTime

@Schema(description = "세션 시작 요청")
data class StudySessionStartRequest(
    @field:Schema(description = "과목 ID", example = "1")
    val subjectId: Long,
)

@Schema(description = "과목 생성/수정 요청")
data class StudySubjectRequest(
    @field:NotBlank
    @field:Size(max = 50)
    @field:Schema(description = "과목명", example = "재정학")
    val name: String,
)

@Schema(description = "과목 순서 변경 요청")
data class StudySubjectReorderRequest(
    @field:NotEmpty
    @field:Schema(description = "순서대로 정렬된 과목 ID 목록", example = "[3, 1, 2]")
    val subjectIds: List<Long>,
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

@Schema(description = "일시정지 타입 응답")
data class PauseTypeResponse(
    val value: PauseType,
    val label: String,
)

fun PauseType.toResponse(): PauseTypeResponse = PauseTypeResponse(value = this, label = label)

@Schema(description = "일시정지 타입 변경 요청")
data class StudyPauseTypeRequest(
    @field:Schema(description = "일시정지 타입", example = "LUNCH")
    val type: PauseType,
)

@Schema(description = "일시정지 응답")
data class StudyPauseResponse(
    val id: Long,
    val pausedAt: LocalDateTime,
    val resumedAt: LocalDateTime?,
    val type: PauseType,
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
        type = type,
    )

// --- Daily Goal ---

@Schema(description = "일일 목표 설정 요청")
data class StudyDailyGoalRequest(
    @field:Schema(description = "날짜", example = "2026-03-10")
    val date: LocalDate,

    @field:Min(1)
    @field:Schema(description = "목표 시간(분)", example = "180")
    val goalMinutes: Int,
)

@Schema(description = "오늘 목표 응답")
data class StudyDailyGoalTodayResponse(
    @field:Schema(description = "목표 시간(분)", example = "180")
    val goalMinutes: Int?,
)

@Schema(description = "주간 요약 응답")
data class StudyWeeklySummaryResponse(
    @field:Schema(description = "총 목표 시간(분)", example = "960")
    val totalGoalMinutes: Int,
    @field:Schema(description = "총 실제 공부 시간(분)", example = "720")
    val totalActualMinutes: Int,
)
