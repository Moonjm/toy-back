package com.example.backend.dailyrecords.entity

import com.example.backend.categories.Category
import com.example.backend.categories.entity.dummyCategory
import com.example.backend.common.entity.withId
import com.example.backend.dailyrecords.DailyOvereat
import com.example.backend.dailyrecords.DailyRecord
import com.example.backend.dailyrecords.OvereatLevel
import com.example.backend.user.User
import com.example.backend.user.entity.dummyUser
import java.time.LocalDate

fun dummyDailyRecord(
    date: LocalDate = LocalDate.of(2026, 2, 1),
    user: User = dummyUser(),
    category: Category = dummyCategory(),
    memo: String? = null,
    together: Boolean = false,
    id: Long = 1L,
): DailyRecord =
    DailyRecord(
        date = date,
        user = user,
        category = category,
        memo = memo,
        together = together,
    ).withId(id)

fun dummyDailyOvereat(
    date: LocalDate = LocalDate.of(2026, 2, 1),
    user: User = dummyUser(),
    overeatLevel: OvereatLevel = OvereatLevel.MILD,
    id: Long = 1L,
): DailyOvereat =
    DailyOvereat(
        date = date,
        user = user,
        overeatLevel = overeatLevel,
    ).withId(id)
