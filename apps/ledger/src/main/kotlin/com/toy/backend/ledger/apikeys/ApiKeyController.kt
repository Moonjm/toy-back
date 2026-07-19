package com.toy.backend.ledger.apikeys

import com.toy.backend.common.response.DataResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "가계부 API 키", description = "단축어용 API 키 관리")
@RestController
@RequestMapping("/api-keys")
class ApiKeyController(
    private val service: ApiKeyService,
) {
    @GetMapping
    @Operation(summary = "API 키 목록 (해시·원본 미노출)")
    fun list(authentication: Authentication): ResponseEntity<DataResponseBody<List<ApiKeyResponse>>> =
        ResponseEntity.ok(DataResponseBody(service.list(authentication.name)))

    @PostMapping
    @Operation(summary = "API 키 발급 — 원본 키는 이 응답에서만 확인 가능")
    fun issue(
        @Valid @RequestBody request: ApiKeyCreateRequest,
        authentication: Authentication,
    ): ResponseEntity<DataResponseBody<ApiKeyIssueResponse>> =
        ResponseEntity.ok(DataResponseBody(service.issue(authentication.name, request)))

    @DeleteMapping("/{id}")
    @Operation(summary = "API 키 폐기")
    fun delete(
        @PathVariable id: Long,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.delete(authentication.name, id)
        return ResponseEntity.noContent().build()
    }
}
