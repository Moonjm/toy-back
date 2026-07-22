package com.toy.backend.ledger.entries

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
import java.time.YearMonth

@Tag(name = "가계부 통계", description = "월별 추이·출처 구성·가맹점 TOP 등 집계")
@RestController
@RequestMapping("/entries/statistics")
class LedgerStatisticsController(
    private val service: LedgerStatisticsService,
) {
    @GetMapping
    @Operation(summary = "기준월 통계 — 최근 6개월 추이, 출처별·가맹점별 집계, 외화, 최대 단건, 일평균")
    fun statistics(
        @Parameter(description = "기준 연월", example = "2026-07")
        @RequestParam
        @DateTimeFormat(pattern = "yyyy-MM")
        yearMonth: YearMonth,
        authentication: Authentication,
    ): ResponseEntity<DataResponseBody<LedgerStatisticsResponse>> =
        ResponseEntity.ok(DataResponseBody(service.statistics(authentication.name, yearMonth)))

    @GetMapping("/yearly")
    @Operation(summary = "기준연 통계 — 12개월 추이, 지난해 비교, 출처별·가맹점별 집계, 외화, 최대 단건, 일평균")
    fun yearlyStatistics(
        @Parameter(description = "기준 연도", example = "2026")
        @RequestParam
        year: Int,
        authentication: Authentication,
    ): ResponseEntity<DataResponseBody<LedgerStatisticsResponse>> =
        ResponseEntity.ok(DataResponseBody(service.yearlyStatistics(authentication.name, year)))
}
