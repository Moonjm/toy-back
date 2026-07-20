package com.toy.backend.ledger.categories

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CategoryRequest(
    @field:NotBlank
    @field:Size(max = 50)
    val name: String,
)

data class CategoryResponse(
    val id: Long,
    val name: String,
)

fun Category.toResponse(): CategoryResponse = CategoryResponse(id = requiredId, name = name)
