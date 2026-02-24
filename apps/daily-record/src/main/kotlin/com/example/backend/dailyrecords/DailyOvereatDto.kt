package com.example.backend.dailyrecords

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDate

@Schema(description = "과식 단계 응답")
data class DailyOvereatResponse(
    @field:Schema(description = "날짜", example = "2026-02-01")
    val date: LocalDate,
    @field:Schema(description = "과식 단계", example = "MILD")
    val overeatLevel: OvereatLevel,
)

fun DailyOvereat.toResponse(): DailyOvereatResponse =
    DailyOvereatResponse(
        date = date,
        overeatLevel = overeatLevel,
    )

@Schema(description = "과식 단계 설정 요청")
data class DailyOvereatRequest(
    @field:Schema(description = "날짜", example = "2026-02-01")
    val date: LocalDate,
    @field:Schema(description = "과식 단계", example = "MILD")
    val overeatLevel: OvereatLevel,
)
