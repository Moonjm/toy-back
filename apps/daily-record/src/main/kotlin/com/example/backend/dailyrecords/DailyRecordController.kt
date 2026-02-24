package com.example.backend.dailyrecords

import com.example.backend.common.annotation.ResponseCreated
import com.example.backend.common.response.DataResponseBody
import com.example.backend.common.response.ErrorResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
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

@Tag(name = "일상기록", description = "일상기록 API")
@RestController
@RequestMapping("/daily-records")
class DailyRecordController(
    private val service: DailyRecordService,
) {
    @GetMapping
    @Operation(summary = "일상기록 목록 조회")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "성공"),
        ],
    )
    fun list(
        @Parameter(description = "조회 날짜", example = "2026-02-01")
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        date: LocalDate?,
        @Parameter(description = "조회 시작 날짜", example = "2026-02-01")
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        from: LocalDate?,
        @Parameter(description = "조회 종료 날짜", example = "2026-02-28")
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        to: LocalDate?,
        authentication: Authentication,
    ): ResponseEntity<DataResponseBody<List<DailyRecordResponse>>> =
        ResponseEntity.ok(DataResponseBody(service.list(authentication.name, date, from, to)))

    @PostMapping
    @ResponseCreated("/daily-records/{id}")
    @Operation(summary = "일상기록 생성")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "201", description = "생성됨"),
            ApiResponse(
                responseCode = "400",
                description = "잘못된 요청",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun create(
        @Valid @RequestBody request: DailyRecordRequest,
        authentication: Authentication,
    ): ResponseEntity<Long> = ResponseEntity.ok(service.create(authentication.name, request))

    @PutMapping("/{id}")
    @Operation(summary = "일상기록 수정")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "수정됨"),
            ApiResponse(
                responseCode = "400",
                description = "잘못된 요청",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "찾을 수 없음",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun update(
        @PathVariable id: Long,
        @Valid @RequestBody request: DailyRecordRequest,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.update(authentication.name, id, request)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "일상기록 삭제")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "삭제됨"),
            ApiResponse(
                responseCode = "404",
                description = "찾을 수 없음",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun delete(
        @Parameter(description = "일상기록 ID", example = "1")
        @PathVariable id: Long,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.delete(authentication.name, id)
        return ResponseEntity.noContent().build()
    }
}
