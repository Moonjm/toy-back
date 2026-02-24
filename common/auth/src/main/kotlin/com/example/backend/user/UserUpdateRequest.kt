package com.example.backend.user

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size
import java.time.LocalDate

@Schema(description = "내 정보 수정 요청")
data class UserUpdateRequest(
    @field:Schema(description = "이름", example = "홍길동")
    @field:Size(max = 50)
    val name: String? = null,
    @field:Schema(description = "성별", example = "MALE", allowableValues = ["MALE", "FEMALE"])
    val gender: String? = null,
    @field:Schema(description = "생년월일", example = "1990-01-15")
    val birthDate: LocalDate? = null,
    @field:Schema(description = "기존 비밀번호", example = "oldPassword123")
    val currentPassword: String? = null,
    @field:Schema(description = "비밀번호", example = "password123")
    val password: String? = null,
)
