package com.toy.backend.ledger.inbound

import com.toy.backend.common.response.DataResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "가계부 수신", description = "문자/카카오페이 원문 수신 API (X-API-Key 또는 JWT)")
@RestController
@RequestMapping("/api/ledger/inbound")
class InboundController(
    private val service: InboundService,
) {
    @PostMapping
    @Operation(summary = "원문 텍스트 수신 — 파싱 실패도 200이며 원문은 항상 보존된다")
    fun receive(
        @Valid @RequestBody request: InboundRequest,
        authentication: Authentication,
    ): ResponseEntity<DataResponseBody<InboundResponse>> =
        ResponseEntity.ok(DataResponseBody(service.process(authentication.name, request.text)))
}
