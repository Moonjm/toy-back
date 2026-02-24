package com.example.backend.dailyrecords.dto

import com.example.backend.dailyrecords.DailyOvereatRequest
import com.example.backend.dailyrecords.DailyRecordRequest
import com.example.backend.dailyrecords.OvereatLevel
import java.time.LocalDate

fun dummyDailyRecordRequest(
    date: LocalDate = LocalDate.of(2026, 2, 1),
    categoryId: Long = 1L,
    memo: String? = null,
    together: Boolean = false,
) = DailyRecordRequest(date = date, categoryId = categoryId, memo = memo, together = together)

fun dummyDailyOvereatRequest(
    date: LocalDate = LocalDate.of(2026, 2, 1),
    overeatLevel: OvereatLevel = OvereatLevel.MILD,
) = DailyOvereatRequest(date = date, overeatLevel = overeatLevel)
