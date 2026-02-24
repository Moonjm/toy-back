package com.example.backend.pair

import com.example.backend.user.User
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Schema(description = "페어 초대 코드 응답")
data class PairInviteResponse(
    @field:Schema(description = "초대 코드", example = "A1B2C3")
    val inviteCode: String,
)

@Schema(description = "페어 상태 응답")
data class PairResponse(
    @field:Schema(description = "페어 ID", example = "1")
    val id: Long,
    @field:Schema(description = "상태", example = "CONNECTED")
    val status: PairStatus,
    @field:Schema(description = "상대방 이름", example = "홍길동")
    val partnerName: String?,
    @field:Schema(description = "연결 일시")
    val connectedAt: String?,
    @field:Schema(description = "상대방 성별", example = "FEMALE")
    val partnerGender: String? = null,
    @field:Schema(description = "상대방 생년월일", example = "1995-03-20")
    val partnerBirthDate: String? = null,
)

@Schema(description = "페어 초대 수락 요청")
data class PairAcceptRequest(
    @field:Schema(description = "초대 코드", example = "A1B2C3")
    @field:NotBlank
    @field:Size(min = 6, max = 6)
    val inviteCode: String,
)

fun PairConnection.toResponse(currentUser: User): PairResponse {
    val other = if (inviter.requiredId == currentUser.requiredId) partner else inviter
    return PairResponse(
        id = requiredId,
        status = status,
        partnerName = other?.name,
        connectedAt = connectedAt?.toString(),
        partnerGender = other?.gender?.name,
        partnerBirthDate = other?.birthDate?.toString(),
    )
}
