package com.example.backend.dailyrecords

import com.example.backend.common.response.DataResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@Tag(name = "과식 단계", description = "과식 단계 API")
@RestController
@RequestMapping("/daily-overeats")
class DailyOvereatController(
    private val service: DailyOvereatService,
) {
    @GetMapping
    @Operation(summary = "과식 단계 목록 조회")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "성공"),
        ],
    )
    fun list(
        @Parameter(description = "조회 시작 날짜", example = "2026-02-01")
        @RequestParam
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        from: LocalDate,
        @Parameter(description = "조회 종료 날짜", example = "2026-02-28")
        @RequestParam
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        to: LocalDate,
        authentication: Authentication,
    ): ResponseEntity<DataResponseBody<List<DailyOvereatResponse>>> =
        ResponseEntity.ok(DataResponseBody(service.list(authentication.name, from, to)))

    @PutMapping
    @Operation(summary = "과식 단계 설정")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "수정됨"),
        ],
    )
    fun upsert(
        @Valid @RequestBody request: DailyOvereatRequest,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.upsert(authentication.name, request)
        return ResponseEntity.noContent().build()
    }
}
