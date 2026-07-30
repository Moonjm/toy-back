package com.toy.backend.user

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Past
import jakarta.validation.constraints.Size
import java.time.LocalDate

@Schema(description = "내 정보 수정 요청")
data class UserUpdateRequest(
    @field:Schema(description = "이름", example = "홍길동")
    @field:Size(max = 50)
    val name: String? = null,
    @field:Schema(description = "성별", example = "MALE", allowableValues = ["MALE", "FEMALE"])
    val gender: String? = null,
    // 미래 날짜면 나이가 음수가 되고, 그 나이로 계산한 식단 목표가 끼니 스냅샷에 영구히 복사된다.
    @field:Schema(description = "생년월일", example = "1990-01-15")
    @field:Past
    val birthDate: LocalDate? = null,
    @field:Schema(description = "회원카드 바코드 번호 — 미전송(null)이면 유지, 빈 문자열이면 삭제", example = "220290031")
    @field:Size(max = 50)
    val membershipBarcode: String? = null,
    @field:Schema(description = "기존 비밀번호", example = "oldPassword123")
    val currentPassword: String? = null,
    @field:Schema(description = "비밀번호", example = "password123")
    val password: String? = null,
)
