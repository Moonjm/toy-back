package com.toy.backend.ledger.recurring

import com.toy.backend.common.annotation.ResponseCreated
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
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "가계부 반복 규칙", description = "월 반복 입력 규칙 관리")
@RestController
@RequestMapping("/api/ledger/recurring-rules")
class RecurringRuleController(
    private val service: RecurringRuleService,
) {
    @GetMapping
    @Operation(summary = "반복 규칙 목록 (관리 페이지)")
    fun list(authentication: Authentication): ResponseEntity<DataResponseBody<List<RecurringRuleResponse>>> =
        ResponseEntity.ok(DataResponseBody(service.list(authentication.name)))

    @PostMapping
    @ResponseCreated("/api/ledger/recurring-rules/{id}")
    @Operation(summary = "반복 규칙 등록 — 내역 상세의 반복 버튼 (entry 값 복사)")
    fun create(
        @Valid @RequestBody request: RecurringRuleCreateRequest,
        authentication: Authentication,
    ): ResponseEntity<Long> = ResponseEntity.ok(service.create(authentication.name, request))

    @PutMapping("/{id}")
    @Operation(summary = "반복 규칙 수정")
    fun update(
        @PathVariable id: Long,
        @Valid @RequestBody request: RecurringRuleUpdateRequest,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.update(authentication.name, id, request)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "반복 규칙 삭제")
    fun delete(
        @PathVariable id: Long,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.delete(authentication.name, id)
        return ResponseEntity.noContent().build()
    }
}
