package com.example.backend.auth

import com.example.backend.common.response.ErrorResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "인증", description = "인증 API")
@RestController
@RequestMapping("/auth")
class AuthController(
    private val authService: AuthService,
) {
    @PostMapping("/login")
    @Operation(summary = "로그인")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "성공"),
            ApiResponse(
                responseCode = "400",
                description = "잘못된 요청",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun login(
        @Valid @RequestBody request: LoginRequest,
        response: HttpServletResponse,
    ): ResponseEntity<Void> {
        authService.login(request, response)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/refresh")
    @Operation(summary = "토큰 갱신")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "성공"),
            ApiResponse(
                responseCode = "400",
                description = "잘못된 요청",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun refresh(
        @CookieValue(name = "refresh_token", required = false) refreshToken: String?,
        response: HttpServletResponse,
    ): ResponseEntity<Void> {
        if (refreshToken.isNullOrBlank()) {
            return ResponseEntity.badRequest().build()
        }
        authService.refresh(refreshToken, response)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/logout")
    @Operation(summary = "로그아웃")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "성공"),
        ],
    )
    fun logout(
        @CookieValue(name = "refresh_token", required = false) refreshToken: String?,
        response: HttpServletResponse,
    ): ResponseEntity<Void> {
        authService.logout(refreshToken, response)
        return ResponseEntity.noContent().build()
    }
}
