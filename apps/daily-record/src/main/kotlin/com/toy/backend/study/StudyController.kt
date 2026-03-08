package com.toy.backend.study

import com.toy.backend.common.response.DataResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@Tag(name = "공부", description = "공부 타이머 API")
@RestController
@RequestMapping("/study")
class StudyController(
    private val service: StudySessionService,
) {
    @GetMapping("/subjects")
    @Operation(summary = "과목 목록 조회")
    fun subjects(): ResponseEntity<List<StudySubjectResponse>> =
        ResponseEntity.ok(StudySubject.entries.map { it.toResponse() })

    @GetMapping("/sessions")
    @Operation(summary = "공부 세션 목록 조회")
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
    ): ResponseEntity<DataResponseBody<List<StudySessionResponse>>> =
        ResponseEntity.ok(DataResponseBody(service.list(authentication.name, date, from, to)))

    @PostMapping("/sessions")
    @Operation(summary = "공부 세션 시작")
    fun start(
        @Valid @RequestBody request: StudySessionStartRequest,
        authentication: Authentication,
    ): ResponseEntity<Long> = ResponseEntity.ok(service.start(authentication.name, request))

    @PatchMapping("/sessions/{id}/pause")
    @Operation(summary = "공부 세션 일시정지")
    fun pause(
        @PathVariable id: Long,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.pause(authentication.name, id)
        return ResponseEntity.noContent().build()
    }

    @PatchMapping("/sessions/{id}/resume")
    @Operation(summary = "공부 세션 재개")
    fun resume(
        @PathVariable id: Long,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.resume(authentication.name, id)
        return ResponseEntity.noContent().build()
    }

    @PatchMapping("/sessions/{id}/end")
    @Operation(summary = "공부 세션 종료")
    fun end(
        @PathVariable id: Long,
        authentication: Authentication,
    ): ResponseEntity<StudySessionResponse> = ResponseEntity.ok(service.end(authentication.name, id))
}
