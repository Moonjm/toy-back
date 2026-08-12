package com.toy.backend.diet.daily

import com.toy.backend.common.response.DataResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@Tag(name = "식단 하루 집계", description = "하루 총 섭취량·점수·마감 피드백")
@RestController
@RequestMapping("/diet/days")
class DailyDietController(
    private val service: DailyDietService,
) {
    @GetMapping("/{date}")
    @Operation(summary = "하루 집계 조회 — 마감 피드백은 조회 시점에 lazy 생성한다")
    fun getDay(
        @Parameter(description = "조회 날짜", example = "2026-07-29")
        @PathVariable
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
        authentication: Authentication,
    ): ResponseEntity<DataResponseBody<DayResponse>> = ResponseEntity.ok(DataResponseBody(service.getDay(authentication.name, date)))
}
