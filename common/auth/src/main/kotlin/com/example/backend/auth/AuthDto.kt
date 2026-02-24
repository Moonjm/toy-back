package com.example.backend.auth

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

@Schema(description = "회원가입 요청")
data class RegisterRequest(
    @field:Schema(description = "아이디", example = "user1")
    @field:NotBlank
    val username: String,
    @field:Schema(description = "이름", example = "홍길동")
    @field:NotBlank
    val name: String,
    @field:Schema(description = "비밀번호", example = "password123")
    @field:NotBlank
    val password: String,
)

@Schema(description = "로그인 요청")
data class LoginRequest(
    @field:Schema(description = "아이디", example = "user1")
    @field:NotBlank
    val username: String,
    @field:Schema(description = "비밀번호", example = "password123")
    @field:NotBlank
    val password: String,
)
