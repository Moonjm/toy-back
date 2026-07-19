package com.toy.backend.ledger.entries

import com.toy.backend.common.annotation.ResponseCreated
import com.toy.backend.common.response.DataResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@Tag(name = "가계부 내역", description = "가계부 내역 API")
@RestController
@RequestMapping("/entries")
class LedgerEntryController(
    private val service: LedgerEntryService,
) {
    @GetMapping
    @Operation(summary = "내역 기간 조회")
    fun list(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
        authentication: Authentication,
    ): ResponseEntity<DataResponseBody<List<LedgerEntryResponse>>> =
        ResponseEntity.ok(DataResponseBody(service.list(authentication.name, from, to)))

    @PostMapping
    @ResponseCreated("/entries/{id}")
    @Operation(summary = "내역 생성")
    fun create(
        @Valid @RequestBody request: LedgerEntryRequest,
        authentication: Authentication,
    ): ResponseEntity<Long> = ResponseEntity.ok(service.create(authentication.name, request))

    @PutMapping("/{id}")
    @Operation(summary = "내역 수정")
    fun update(
        @PathVariable id: Long,
        @Valid @RequestBody request: LedgerEntryRequest,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.update(authentication.name, id, request)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "내역 삭제")
    fun delete(
        @PathVariable id: Long,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.delete(authentication.name, id)
        return ResponseEntity.noContent().build()
    }
}
