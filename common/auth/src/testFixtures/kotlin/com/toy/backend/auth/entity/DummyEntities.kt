package com.toy.backend.auth.entity

import com.toy.backend.auth.security.RefreshToken
import com.toy.backend.common.entity.withId
import com.toy.backend.user.User
import com.toy.backend.user.entity.dummyUser
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
