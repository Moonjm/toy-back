package com.toy.backend.dispatch

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import java.time.LocalDate

/** 무인증으로 나가는 응답이다 — **실명·차량번호를 넣지 않는다.** */
data class ShiftDayResponse(
    val date: LocalDate,
    val role: DispatchRole,
    val working: Boolean,
    val slot: Int?,
    val note: String?,
)

data class ShiftRangeResponse(
    val days: List<ShiftDayResponse>,
)

data class ShiftSaveRequest(
    @field:NotNull val role: DispatchRole,
    @field:NotEmpty val days: List<ShiftSaveDay>,
)

data class ShiftSaveDay(
    @field:NotNull val date: LocalDate,
    @field:NotNull val working: Boolean,
    val slot: Int?,
    val note: String?,
)

data class PatternSaveRequest(
    @field:Min(1) val cycleDays: Int,
    @field:NotEmpty val workingOffsets: List<Int>,
    @field:NotNull val anchorDate: LocalDate,
)

data class PatternResponse(
    val role: DispatchRole,
    val cycleDays: Int,
    val workingOffsets: List<Int>,
    val anchorDate: LocalDate,
)
