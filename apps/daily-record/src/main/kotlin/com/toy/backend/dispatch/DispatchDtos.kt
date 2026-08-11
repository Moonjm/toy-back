package com.toy.backend.dispatch

import jakarta.validation.constraints.NotEmpty
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
    val role: DispatchRole,
    @field:NotEmpty val days: List<ShiftSaveDay>,
)

data class ShiftSaveDay(
    val date: LocalDate,
    val working: Boolean,
    val slot: Int?,
    val note: String?,
)

enum class MatchedBy { NAME, ROW_INDEX }

data class RecognitionDay(
    val day: Int,
    val working: Boolean,
    val slot: Int?,
    val note: String?,
    /** 겹친 구간에서 두 조각의 답이 갈렸다. 검수 화면이 강조한다. */
    val conflict: Boolean,
)

/** **실명·차량번호를 싣지 않는다.** 모델이 읽더라도 버린다 — 앱 로그·캐시에 남는다. */
data class RecognitionResponse(
    val yearMonth: String,
    val hasNameColumn: Boolean,
    val matchedBy: MatchedBy,
    val rowIndex: Int,
    val rowCount: Int,
    val warnings: List<String>,
    val days: List<RecognitionDay>,
)
