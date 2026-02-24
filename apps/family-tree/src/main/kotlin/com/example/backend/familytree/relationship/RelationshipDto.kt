package com.example.backend.familytree.relationship

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "배우자 관계 요청")
data class SpouseRequest(
    @field:Schema(description = "인물 ID", example = "1")
    val personId: Long,
    @field:Schema(description = "배우자 인물 ID", example = "2")
    val spouseId: Long,
)

@Schema(description = "부모-자식 관계 요청")
data class ParentChildRequest(
    @field:Schema(description = "부모 인물 ID", example = "1")
    val parentId: Long,
    @field:Schema(description = "자식 인물 ID", example = "2")
    val childId: Long,
)

@Schema(description = "배우자 관계 응답")
data class SpouseResponse(
    @field:Schema(description = "ID", example = "1")
    val id: Long,
    @field:Schema(description = "인물 A ID (작은 값)", example = "1")
    val personAId: Long,
    @field:Schema(description = "인물 B ID (큰 값)", example = "2")
    val personBId: Long,
)

@Schema(description = "부모-자식 관계 응답")
data class ParentChildResponse(
    @field:Schema(description = "ID", example = "1")
    val id: Long,
    @field:Schema(description = "부모 인물 ID", example = "1")
    val parentId: Long,
    @field:Schema(description = "자식 인물 ID", example = "2")
    val childId: Long,
)

fun Spouse.toResponse(): SpouseResponse =
    SpouseResponse(
        id = requiredId,
        personAId = personAId,
        personBId = personBId,
    )

fun ParentChild.toResponse(): ParentChildResponse =
    ParentChildResponse(
        id = requiredId,
        parentId = parentId,
        childId = childId,
    )
