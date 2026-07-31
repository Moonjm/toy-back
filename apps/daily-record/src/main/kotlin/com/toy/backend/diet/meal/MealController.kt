package com.toy.backend.diet.meal

import com.toy.backend.common.annotation.ResponseCreated
import com.toy.backend.common.response.DataResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
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

@Tag(name = "끼니", description = "확정된 끼니 기록")
@RestController
@RequestMapping("/diet/meals")
class MealController(
    private val service: MealService,
) {
    @PostMapping
    @ResponseCreated("/diet/meals/{id}")
    @Operation(summary = "끼니 확정 — 사용자가 확인·수정한 항목을 최종본으로 받는다")
    fun confirm(
        @Valid @RequestBody request: MealConfirmRequest,
        authentication: Authentication,
    ): ResponseEntity<Long> = ResponseEntity.ok(service.confirm(authentication.name, request))

    @GetMapping("/{id}")
    @Operation(summary = "끼니 단건 조회 (피드백 완료 폴링용)")
    fun get(
        @PathVariable id: Long,
        authentication: Authentication,
    ): ResponseEntity<DataResponseBody<MealResponse>> = ResponseEntity.ok(DataResponseBody(service.get(authentication.name, id)))

    @GetMapping
    @Operation(summary = "기간별 끼니 목록")
    fun list(
        @Parameter(description = "시작일", example = "2026-07-01")
        @RequestParam
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @Parameter(description = "종료일", example = "2026-07-31")
        @RequestParam
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
        authentication: Authentication,
    ): ResponseEntity<DataResponseBody<List<MealResponse>>> =
        ResponseEntity.ok(DataResponseBody(service.list(authentication.name, from, to)))

    @PutMapping("/{id}/items")
    @Operation(summary = "항목 전체 교체 — 영양소·점수·피드백을 재계산한다")
    fun updateItems(
        @PathVariable id: Long,
        @Valid @RequestBody request: MealItemsRequest,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.updateItems(authentication.name, id, request)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "끼니 삭제 — 사진은 detach 후 정리 배치가 수거한다")
    fun delete(
        @PathVariable id: Long,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.delete(authentication.name, id)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{mealId}/photos/{fileId}")
    @Operation(summary = "사진 한 장 삭제 — detach 후 정리 배치가 수거한다. 점수·피드백은 그대로다")
    fun deletePhoto(
        @PathVariable mealId: Long,
        @PathVariable fileId: Long,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.deletePhoto(authentication.name, mealId, fileId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/retry")
    @Operation(summary = "피드백 재생성 (FAILED 상태에서만)")
    fun retryFeedback(
        @PathVariable id: Long,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.retryFeedback(authentication.name, id)
        return ResponseEntity.noContent().build()
    }
}
