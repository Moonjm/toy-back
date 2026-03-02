package com.toy.backend.holidays

import com.toy.backend.common.response.DataResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "공휴일", description = "공휴일 API")
@Validated
@RestController
@RequestMapping("/holidays")
class HolidayController(
    private val service: HolidayService,
) {
    @GetMapping
    @Operation(summary = "공휴일 목록 조회")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "성공"),
        ],
    )
    fun getHolidays(
        @Parameter(description = "조회 연도", example = "2026")
        @RequestParam
        @Min(2000)
        @Max(2100)
        year: Int,
    ): ResponseEntity<DataResponseBody<Map<String, List<String>>>> = ResponseEntity.ok(DataResponseBody(service.getHolidaysByYear(year)))

    @PostMapping("/{year}")
    @PreAuthorize("hasAuthority('ADMIN')")
    @Operation(summary = "공휴일 데이터 갱신")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "갱신 완료"),
        ],
    )
    fun refresh(
        @Parameter(description = "갱신 연도", example = "2026")
        @PathVariable
        @Min(2000)
        @Max(2100)
        year: Int,
    ): ResponseEntity<Void> {
        service.fetchAndSaveHolidays(year)
        return ResponseEntity.noContent().build()
    }
}
