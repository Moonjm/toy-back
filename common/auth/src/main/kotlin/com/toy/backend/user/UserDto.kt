package com.toy.backend.user

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "사용자 응답")
data class UserResponse(
    @field:Schema(description = "ID", example = "1")
    val id: Long,
    @field:Schema(description = "아이디", example = "admin")
    val username: String,
    @field:Schema(description = "이름", example = "홍길동")
    val name: String,
    @field:Schema(description = "권한", example = "USER", allowableValues = ["USER", "ADMIN"])
    val authority: Authority,
    @field:Schema(description = "성별", example = "MALE", allowableValues = ["MALE", "FEMALE"])
    val gender: String? = null,
    @field:Schema(description = "생년월일", example = "1990-01-15")
    val birthDate: String? = null,
    @field:Schema(description = "계정 잠금 여부", example = "false")
    val locked: Boolean = false,
)

fun User.toResponse(): UserResponse =
    UserResponse(
        id = requiredId,
        username = username,
        name = name,
        authority = authority,
        gender = gender?.name,
        birthDate = birthDate?.toString(),
        locked = locked,
    )
