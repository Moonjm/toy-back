package com.example.backend.auth.security

import com.example.backend.common.entity.BaseEntity
import com.example.backend.user.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "refresh_tokens")
class RefreshToken(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,
    @Column(nullable = false, length = 64, unique = true)
    var tokenHash: String,
    @Column(nullable = false)
    var expiresAt: LocalDateTime,
    @Column(nullable = true)
    var revokedAt: LocalDateTime? = null,
) : BaseEntity()
