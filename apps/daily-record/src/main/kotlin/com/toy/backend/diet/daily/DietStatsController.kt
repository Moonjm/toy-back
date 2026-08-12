package com.toy.backend.diet.daily

import com.toy.backend.common.response.DataResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@Tag(name = "식단 기간 통계", description = "주·월 통계 — 기간만 바꿔 같은 엔드포인트를 쓴다")
@RestController
@RequestMapping("/diet/stats")
class DietStatsController(
    private val service: DietStatsService,
) {
    @GetMapping
    @Operation(summary = "기간 통계 — 평균은 기록한 날로만 낸다")
    fun stats(
        @Parameter(description = "시작일", example = "2026-07-22")
        @RequestParam
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @Parameter(description = "종료일", example = "2026-07-28")
        @RequestParam
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
        authentication: Authentication,
    ): ResponseEntity<DataResponseBody<DietStatsResponse>> =
        ResponseEntity.ok(DataResponseBody(service.stats(authentication.name, from, to)))
}
