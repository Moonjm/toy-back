package com.toy.backend.pair.dto

import com.toy.backend.pair.PairAcceptRequest
import com.toy.backend.pair.event.PairEventRequest
import java.time.LocalDate

fun dummyPairAcceptRequest(inviteCode: String = "ABC123") = PairAcceptRequest(inviteCode = inviteCode)

fun dummyPairEventRequest(
    title: String = "기념일",
    emoji: String = "🎉",
    eventDate: LocalDate = LocalDate.of(2026, 3, 14),
    recurring: Boolean = true,
) = PairEventRequest(title = title, emoji = emoji, eventDate = eventDate, recurring = recurring)
