package com.example.backend.pair

import com.example.backend.common.response.DataResponseBody
import com.example.backend.common.response.ErrorResponseBody
import com.example.backend.dailyrecords.DailyRecordResponse
import com.example.backend.pair.event.PairEventRequest
import com.example.backend.pair.event.PairEventResponse
import com.example.backend.pair.event.PairEventService
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
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@Tag(name = "페어", description = "페어(2인 캘린더 공유) API")
@RestController
@RequestMapping("/pair")
class PairController(
    private val service: PairService,
    private val eventService: PairEventService,
) {
    @PostMapping("/invite")
    @Operation(summary = "초대 코드 생성")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "성공"),
            ApiResponse(
                responseCode = "400",
                description = "이미 페어가 존재합니다",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun createInvite(authentication: Authentication): ResponseEntity<DataResponseBody<PairInviteResponse>> =
        ResponseEntity.ok(DataResponseBody(service.createInvite(authentication.name)))

    @PostMapping("/accept")
    @Operation(summary = "초대 수락")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "성공"),
            ApiResponse(
                responseCode = "400",
                description = "잘못된 요청",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "초대 코드를 찾을 수 없음",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun acceptInvite(
        @Valid @RequestBody request: PairAcceptRequest,
        authentication: Authentication,
    ): ResponseEntity<DataResponseBody<PairResponse>> =
        ResponseEntity.ok(DataResponseBody(service.acceptInvite(authentication.name, request)))

    @GetMapping
    @Operation(summary = "페어 상태 조회")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "성공"),
        ],
    )
    fun getStatus(authentication: Authentication): ResponseEntity<DataResponseBody<PairResponse?>> =
        ResponseEntity.ok(DataResponseBody(service.getStatus(authentication.name)))

    @DeleteMapping
    @Operation(summary = "페어 해제")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "삭제됨"),
            ApiResponse(
                responseCode = "404",
                description = "페어를 찾을 수 없음",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun unpair(authentication: Authentication): ResponseEntity<Void> {
        service.unpair(authentication.name)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/daily-records")
    @Operation(summary = "상대방 일상기록 조회")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "성공"),
            ApiResponse(
                responseCode = "400",
                description = "페어가 연결되지 않았습니다",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun getPartnerDailyRecords(
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
        ResponseEntity.ok(
            DataResponseBody(service.getPartnerDailyRecords(authentication.name, date, from, to)),
        )

    @GetMapping("/events")
    @Operation(summary = "페어 이벤트 목록 조회")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "성공"),
            ApiResponse(
                responseCode = "400",
                description = "페어가 연결되지 않았습니다",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun listEvents(
        @Parameter(description = "조회 시작 날짜", example = "2026-02-01")
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        from: LocalDate?,
        @Parameter(description = "조회 종료 날짜", example = "2026-02-28")
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        to: LocalDate?,
        authentication: Authentication,
    ): ResponseEntity<DataResponseBody<List<PairEventResponse>>> =
        ResponseEntity.ok(DataResponseBody(eventService.list(authentication.name, from, to)))

    @PostMapping("/events")
    @Operation(summary = "페어 이벤트 등록")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "생성됨"),
            ApiResponse(
                responseCode = "400",
                description = "페어가 연결되지 않았습니다",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun createEvent(
        @Valid @RequestBody request: PairEventRequest,
        authentication: Authentication,
    ): ResponseEntity<DataResponseBody<Long>> = ResponseEntity.ok(DataResponseBody(eventService.create(authentication.name, request)))

    @DeleteMapping("/events/{id}")
    @Operation(summary = "페어 이벤트 삭제")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "삭제됨"),
            ApiResponse(
                responseCode = "404",
                description = "이벤트를 찾을 수 없음",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun deleteEvent(
        @PathVariable id: Long,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        eventService.delete(authentication.name, id)
        return ResponseEntity.noContent().build()
    }
}
