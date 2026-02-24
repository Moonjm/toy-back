package com.toy.backend.familytree.person

import com.toy.backend.user.Gender
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDate

@Schema(description = "인물 요청")
data class PersonRequest(
    @field:Schema(description = "이름", example = "김철수")
    @field:NotBlank
    @field:Size(max = 50)
    val name: String,
    @field:Schema(description = "생년월일", example = "1950-03-15")
    val birthDate: LocalDate? = null,
    @field:Schema(description = "생년월일 역법", example = "SOLAR", allowableValues = ["SOLAR", "LUNAR"])
    val birthDateType: CalendarType? = null,
    @field:Schema(description = "사망일", example = "2020-12-01")
    val deathDate: LocalDate? = null,
    @field:Schema(description = "성별", example = "MALE")
    val gender: Gender? = null,
    @field:Schema(description = "프로필 이미지 파일 ID", example = "42")
    val profileImageId: Long? = null,
    @field:Schema(description = "메모")
    @field:Size(max = 500)
    val memo: String? = null,
)

@Schema(description = "인물 응답")
data class PersonResponse(
    @field:Schema(description = "ID", example = "1")
    val id: Long,
    @field:Schema(description = "이름", example = "김철수")
    val name: String,
    @field:Schema(description = "생년월일")
    val birthDate: LocalDate?,
    @field:Schema(description = "생년월일 역법")
    val birthDateType: CalendarType?,
    @field:Schema(description = "사망일")
    val deathDate: LocalDate?,
    @field:Schema(description = "성별")
    val gender: Gender?,
    @field:Schema(description = "프로필 이미지 Presigned URL")
    val profileImageUrl: String?,
    @field:Schema(description = "메모")
    val memo: String?,
)

fun Person.toResponse(profileImageUrl: String? = null): PersonResponse =
    PersonResponse(
        id = requiredId,
        name = name,
        birthDate = birthDate,
        birthDateType = birthDateType,
        deathDate = deathDate,
        gender = gender,
        profileImageUrl = profileImageUrl,
        memo = memo,
    )
