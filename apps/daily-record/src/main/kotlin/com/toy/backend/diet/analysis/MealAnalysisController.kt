package com.toy.backend.diet.analysis

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
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "식단 인식", description = "사진 인식 → 확인 → 확정 흐름의 인식 단계")
@RestController
@RequestMapping("/diet/analyses")
class MealAnalysisController(
    private val service: MealAnalysisService,
) {
    @PostMapping
    @ResponseCreated("/diet/analyses/{id}")
    @Operation(summary = "사진 인식 요청 — 즉시 201을 주고 인식은 뒤에서 돈다 (최대 5장)")
    fun create(
        @Valid @RequestBody request: AnalysisCreateRequest,
        authentication: Authentication,
    ): ResponseEntity<Long> = ResponseEntity.ok(service.create(authentication.name, request))

    @GetMapping("/{id}")
    @Operation(summary = "인식 상태·결과 조회 (확인 화면·폴링용)")
    fun get(
        @PathVariable id: Long,
        authentication: Authentication,
    ): ResponseEntity<DataResponseBody<AnalysisResponse>> = ResponseEntity.ok(DataResponseBody(service.get(authentication.name, id)))

    @PostMapping("/{id}/retry")
    @Operation(summary = "실패한 사진만 재인식")
    fun retry(
        @PathVariable id: Long,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.retry(authentication.name, id)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "확인 취소")
    fun delete(
        @PathVariable id: Long,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.delete(authentication.name, id)
        return ResponseEntity.noContent().build()
    }
}
