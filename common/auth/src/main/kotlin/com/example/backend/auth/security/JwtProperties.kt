package com.example.backend.auth.security

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "security.jwt")
data class JwtProperties(
    val secret: String,
    val accessTokenExpireMinutes: Long = 120,
    val refreshTokenExpireDays: Long = 14,
)
