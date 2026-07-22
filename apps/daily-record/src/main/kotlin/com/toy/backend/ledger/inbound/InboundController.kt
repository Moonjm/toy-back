package com.toy.backend.ledger.inbound

import com.toy.backend.common.annotation.ResponseCreated
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "가계부 수신", description = "문자/카카오페이 원문 수신 API (X-API-Key 또는 JWT)")
@RestController
@RequestMapping("/inbound")
class InboundController(
    private val service: InboundService,
) {
    @PostMapping
    @ResponseCreated("/inbound/{id}/retry")
    @Operation(summary = "원문 텍스트 수신 — 항상 수신 기록을 생성(201)하며 Location이 재처리 경로를 가리킨다")
    fun receive(
        @Valid @RequestBody request: InboundRequest,
        authentication: Authentication,
    ): ResponseEntity<Long> = ResponseEntity.ok(service.process(authentication.name, request.text))

    @PostMapping("/{id}/retry")
    @Operation(summary = "실패(PARSE_FAILED) 건의 보존된 원문 재처리 — 성공 204, 재실패 400")
    fun retry(
        @PathVariable id: Long,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.retry(authentication.name, id)
        return ResponseEntity.noContent().build()
    }
}
