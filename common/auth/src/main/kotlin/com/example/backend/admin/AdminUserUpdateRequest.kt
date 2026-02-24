package com.example.backend.admin

import com.example.backend.user.Authority
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

@Schema(description = "관리자 사용자 수정 요청")
data class AdminUserUpdateRequest(
    @field:Schema(description = "비밀번호", example = "password123")
    @field:NotBlank
    val password: String,
    @field:Schema(description = "이름", example = "홍길동")
    @field:NotBlank
    val name: String,
    @field:Schema(description = "권한", example = "ADMIN")
    val authority: Authority,
)
