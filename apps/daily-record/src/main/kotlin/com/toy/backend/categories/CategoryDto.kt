package com.toy.backend.categories

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

@Schema(description = "카테고리 응답")
data class CategoryResponse(
    @field:Schema(description = "ID", example = "1")
    val id: Long,
    @field:Schema(description = "이모지", example = "🏋️")
    val emoji: String,
    @field:Schema(description = "이름", example = "헬스")
    val name: String,
    @field:Schema(description = "활성 여부", example = "true")
    val isActive: Boolean,
    @field:Schema(description = "정렬 순서", example = "1")
    val sortOrder: Int,
)

fun Category.toResponse(): CategoryResponse =
    CategoryResponse(
        id = requiredId,
        emoji = emoji,
        name = name,
        isActive = isActive,
        sortOrder = sortOrder,
    )

@Schema(description = "카테고리 요청")
data class CategoryRequest(
    @field:Schema(description = "이모지", example = "🏊")
    @field:NotBlank
    val emoji: String,
    @field:Schema(description = "이름", example = "수영")
    @field:NotBlank
    val name: String,
    @field:Schema(description = "활성 여부", example = "true")
    val isActive: Boolean,
)

@Schema(description = "카테고리 순서 변경 요청")
data class CategoryMoveRequest(
    @field:Schema(description = "이동할 카테고리 ID", example = "3")
    val targetId: Long,
    @field:Schema(description = "앞에 둘 카테고리 ID", example = "8")
    val beforeId: Long? = null,
)
