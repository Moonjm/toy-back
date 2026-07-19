package com.toy.backend.ledger.apikeys

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

data class ApiKeyCreateRequest(
    @field:NotBlank
    @field:Size(max = 50)
    val name: String,
)

/** 원본 키는 발급 응답에서 단 한 번만 노출된다. */
data class ApiKeyIssueResponse(
    val id: Long,
    val name: String,
    val key: String,
)

data class ApiKeyResponse(
    val id: Long,
    val name: String,
    val createdAt: LocalDateTime,
)
