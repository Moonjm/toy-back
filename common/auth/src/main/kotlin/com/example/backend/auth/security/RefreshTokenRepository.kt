package com.example.backend.auth.security

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDateTime

interface RefreshTokenRepository : JpaRepository<RefreshToken, Long> {
    fun findByTokenHashAndRevokedAtIsNullAndExpiresAtAfter(
        tokenHash: String,
        now: LocalDateTime,
    ): RefreshToken?

    fun deleteAllByUserId(userId: Long)
}
