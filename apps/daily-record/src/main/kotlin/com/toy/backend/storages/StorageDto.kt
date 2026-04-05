package com.toy.backend.storages

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDate
import java.time.LocalDateTime

// ── 응답 ──

@Schema(description = "보관함 목록 응답 (구역 + 품목 포함)")
data class StorageResponse(
    @field:Schema(description = "보관함 ID", example = "1")
    val id: Long,
    @field:Schema(description = "보관함 이름", example = "냉장고")
    val name: String,
    @field:Schema(description = "정렬 순서", example = "0")
    val sortOrder: Int,
    @field:Schema(description = "구역 목록")
    val sections: List<SectionResponse>,
)

@Schema(description = "구역 응답")
data class SectionResponse(
    @field:Schema(description = "구역 ID", example = "1")
    val id: Long,
    @field:Schema(description = "구역 이름", example = "윗칸")
    val name: String,
    @field:Schema(description = "정렬 순서", example = "0")
    val sortOrder: Int,
    @field:Schema(description = "품목 목록")
    val items: List<ItemResponse>,
)

@Schema(description = "품목 응답")
data class ItemResponse(
    @field:Schema(description = "품목 ID", example = "1")
    val id: Long,
    @field:Schema(description = "품목 이름", example = "우유")
    val name: String,
    @field:Schema(description = "수량", example = "2")
    val quantity: Int,
    @field:Schema(description = "소비기한", example = "2026-04-08")
    val expiryDate: LocalDate?,
    @field:Schema(description = "등록자 ID", example = "1")
    val createdBy: Long,
    @field:Schema(description = "등록일시")
    val createdAt: LocalDateTime,
)

// ── 요청 ──

@Schema(description = "보관함 생성 요청")
data class StorageCreateRequest(
    @field:Schema(description = "보관함 이름", example = "냉장고")
    @field:NotBlank
    @field:Size(max = 30)
    val name: String,
)

@Schema(description = "보관함 이름 수정 요청")
data class StorageUpdateRequest(
    @field:Schema(description = "보관함 이름", example = "김치냉장고")
    @field:NotBlank
    @field:Size(max = 30)
    val name: String,
)

@Schema(description = "구역 추가 요청")
data class SectionCreateRequest(
    @field:Schema(description = "구역 이름", example = "선반1")
    @field:NotBlank
    @field:Size(max = 20)
    val name: String,
)

@Schema(description = "구역 이름 수정 요청")
data class SectionUpdateRequest(
    @field:Schema(description = "구역 이름", example = "선반2")
    @field:NotBlank
    @field:Size(max = 20)
    val name: String,
)

@Schema(description = "품목 추가/수정 요청")
data class ItemRequest(
    @field:Schema(description = "품목 이름", example = "우유")
    @field:NotBlank
    @field:Size(max = 30)
    val name: String,
    @field:Schema(description = "수량", example = "1")
    @field:Min(1)
    val quantity: Int = 1,
    @field:Schema(description = "소비기한", example = "2026-04-08")
    val expiryDate: LocalDate? = null,
    @field:Schema(description = "구역 ID", example = "1")
    val sectionId: Long,
)

// ── 변환 ──

fun Storage.toResponse(sections: List<SectionResponse>): StorageResponse =
    StorageResponse(
        id = requiredId,
        name = name,
        sortOrder = sortOrder,
        sections = sections,
    )

fun StorageSection.toResponse(items: List<ItemResponse>): SectionResponse =
    SectionResponse(
        id = requiredId,
        name = name,
        sortOrder = sortOrder,
        items = items,
    )

fun StorageItem.toResponse(): ItemResponse =
    ItemResponse(
        id = requiredId,
        name = name,
        quantity = quantity,
        expiryDate = expiryDate,
        createdBy = createdByUser.requiredId,
        createdAt = createdAt,
    )
