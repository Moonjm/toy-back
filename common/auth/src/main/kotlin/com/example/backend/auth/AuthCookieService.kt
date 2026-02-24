package com.example.backend.auth

import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class AuthCookieService {
    fun accessTokenCookie(
        token: String,
        maxAgeSeconds: Long,
    ): ResponseCookie = baseCookie("access_token", token, maxAgeSeconds)

    fun refreshTokenCookie(
        token: String,
        maxAgeSeconds: Long,
    ): ResponseCookie = baseCookie("refresh_token", token, maxAgeSeconds)

    fun clearAccessTokenCookie(): ResponseCookie = baseCookie("access_token", "", 0)

    fun clearRefreshTokenCookie(): ResponseCookie = baseCookie("refresh_token", "", 0)

    fun applyAuthCookies(
        response: HttpServletResponse,
        accessToken: String,
        refreshToken: String,
        accessMaxAgeSeconds: Long,
        refreshMaxAgeSeconds: Long,
    ) {
        response.addHeader(
            HttpHeaders.SET_COOKIE,
            accessTokenCookie(accessToken, accessMaxAgeSeconds).toString(),
        )
        response.addHeader(
            HttpHeaders.SET_COOKIE,
            refreshTokenCookie(refreshToken, refreshMaxAgeSeconds).toString(),
        )
    }

    fun clearAuthCookies(response: HttpServletResponse) {
        response.addHeader(HttpHeaders.SET_COOKIE, clearAccessTokenCookie().toString())
        response.addHeader(HttpHeaders.SET_COOKIE, clearRefreshTokenCookie().toString())
    }

    private fun baseCookie(
        name: String,
        value: String,
        maxAgeSeconds: Long,
    ): ResponseCookie =
        ResponseCookie
            .from(name, value)
            .secure(false)
            .httpOnly(true)
            .sameSite("Lax")
            .path("/")
            .maxAge(maxAgeSeconds)
            .build()
}
