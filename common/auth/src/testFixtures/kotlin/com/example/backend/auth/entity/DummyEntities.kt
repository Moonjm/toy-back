package com.example.backend.auth.entity

import com.example.backend.auth.security.RefreshToken
import com.example.backend.common.entity.withId
import com.example.backend.user.User
import com.example.backend.user.entity.dummyUser
import java.time.LocalDateTime

fun dummyRefreshToken(
    user: User = dummyUser(),
    tokenHash: String = "dummy-token-hash",
    expiresAt: LocalDateTime = LocalDateTime.now().plusDays(14),
    revokedAt: LocalDateTime? = null,
    id: Long = 1L,
): RefreshToken =
    RefreshToken(
        user = user,
        tokenHash = tokenHash,
        expiresAt = expiresAt,
        revokedAt = revokedAt,
    ).withId(id)
