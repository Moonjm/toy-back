package com.example.backend.pair.entity

import com.example.backend.common.entity.withId
import com.example.backend.pair.PairConnection
import com.example.backend.pair.PairStatus
import com.example.backend.pair.event.PairEvent
import com.example.backend.user.User
import com.example.backend.user.entity.dummyUser
import java.time.LocalDate
import java.time.LocalDateTime

fun dummyPairConnection(
    inviter: User = dummyUser(),
    partner: User? = null,
    inviteCode: String = "ABC123",
    status: PairStatus = PairStatus.PENDING,
    connectedAt: LocalDateTime? = null,
    id: Long = 1L,
): PairConnection =
    PairConnection(
        inviter = inviter,
        partner = partner,
        inviteCode = inviteCode,
        status = status,
        connectedAt = connectedAt,
    ).withId(id)

fun dummyPairEvent(
    pair: PairConnection = dummyPairConnection(),
    title: String = "기념일",
    emoji: String = "🎉",
    eventDate: LocalDate = LocalDate.of(2026, 3, 14),
    recurring: Boolean = true,
    id: Long = 1L,
): PairEvent =
    PairEvent(
        pair = pair,
        title = title,
        emoji = emoji,
        eventDate = eventDate,
        recurring = recurring,
    ).withId(id)
