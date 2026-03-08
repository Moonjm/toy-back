package com.toy.backend.study

import com.toy.backend.common.response.DataResponseBody
import com.toy.backend.common.response.ErrorResponseBody
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
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
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
    // --- Subject ---

    @GetMapping("/subjects")
    @Operation(summary = "과목 목록 조회")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "성공"),
        ],
    )
    fun subjects(): ResponseEntity<List<StudySubjectResponse>> =
        ResponseEntity.ok(service.listSubjects())

    @PostMapping("/subjects")
    @Operation(summary = "과목 생성")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "생성된 과목 ID"),
        ],
    )
    fun createSubject(
        @Valid @RequestBody request: StudySubjectRequest,
    ): ResponseEntity<Long> = ResponseEntity.ok(service.createSubject(request))

    @PutMapping("/subjects/{id}")
    @Operation(summary = "과목 수정")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "수정됨"),
            ApiResponse(
                responseCode = "404",
                description = "과목을 찾을 수 없음",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun updateSubject(
        @PathVariable id: Long,
        @Valid @RequestBody request: StudySubjectRequest,
    ): ResponseEntity<Void> {
        service.updateSubject(id, request)
        return ResponseEntity.noContent().build()
    }

    @PutMapping("/subjects/reorder")
    @Operation(summary = "과목 순서 변경")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "순서 변경됨"),
            ApiResponse(
                responseCode = "404",
                description = "과목을 찾을 수 없음",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun reorderSubjects(
        @Valid @RequestBody request: StudySubjectReorderRequest,
    ): ResponseEntity<Void> {
        service.reorderSubjects(request)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/subjects/{id}")
    @Operation(summary = "과목 삭제")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "삭제됨"),
            ApiResponse(
                responseCode = "404",
                description = "과목을 찾을 수 없음",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun deleteSubject(@PathVariable id: Long): ResponseEntity<Void> {
        service.deleteSubject(id)
        return ResponseEntity.noContent().build()
    }

    // --- Session ---

    @GetMapping("/sessions")
    @Operation(summary = "공부 세션 목록 조회")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "성공"),
        ],
    )
    fun list(
        @Parameter(description = "조회 시작 날짜", example = "2026-03-08")
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        from: LocalDate?,
        @Parameter(description = "조회 종료 날짜", example = "2026-03-08")
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        to: LocalDate?,
        authentication: Authentication,
    ): ResponseEntity<DataResponseBody<List<StudySessionResponse>>> =
        ResponseEntity.ok(DataResponseBody(service.list(authentication.name, from, to)))

    @PostMapping("/sessions")
    @Operation(summary = "공부 세션 시작")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "생성된 세션 ID"),
            ApiResponse(
                responseCode = "404",
                description = "과목을 찾을 수 없음",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun start(
        @Valid @RequestBody request: StudySessionStartRequest,
        authentication: Authentication,
    ): ResponseEntity<Long> = ResponseEntity.ok(service.start(authentication.name, request))

    @PatchMapping("/sessions/{id}/pause")
    @Operation(summary = "공부 세션 일시정지")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "일시정지됨"),
            ApiResponse(
                responseCode = "404",
                description = "세션을 찾을 수 없음",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun pause(
        @PathVariable id: Long,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.pause(authentication.name, id)
        return ResponseEntity.noContent().build()
    }

    @PatchMapping("/sessions/{id}/resume")
    @Operation(summary = "공부 세션 재개")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "재개됨"),
            ApiResponse(
                responseCode = "404",
                description = "세션을 찾을 수 없음",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun resume(
        @PathVariable id: Long,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.resume(authentication.name, id)
        return ResponseEntity.noContent().build()
    }

    @PatchMapping("/sessions/{id}/end")
    @Operation(summary = "공부 세션 종료")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "종료된 세션 정보"),
            ApiResponse(
                responseCode = "404",
                description = "세션을 찾을 수 없음",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun end(
        @PathVariable id: Long,
        authentication: Authentication,
    ): ResponseEntity<StudySessionResponse> = ResponseEntity.ok(service.end(authentication.name, id))
}
