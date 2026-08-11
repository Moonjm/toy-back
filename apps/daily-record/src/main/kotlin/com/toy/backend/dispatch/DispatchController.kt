package com.toy.backend.dispatch

import com.toy.backend.common.response.DataResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.time.LocalDate
import java.time.YearMonth

@Tag(name = "근무 달력", description = "배차표 사진 인식 → 검수 → 확정 저장, 그리고 무인증 조회")
@RestController
@RequestMapping("/dispatch")
class DispatchController(
    private val queryService: DispatchQueryService,
    private val commandService: DispatchCommandService,
    private val recognitionService: DispatchRecognitionService,
) {
    /** **무인증으로 열린 단 하나의 엔드포인트다.** 응답에 실명·차량번호가 없다. */
    @GetMapping("/shifts")
    @Operation(summary = "기간 근무 조회 — 아빠(확정분)와 엄마(패턴)를 합쳐 반환")
    fun findShifts(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): ResponseEntity<DataResponseBody<ShiftRangeResponse>> = ResponseEntity.ok(DataResponseBody(queryService.findRange(from, to)))

    /**
     * `yearMonth`를 앱이 보낸다. 사진에서 읽은 값으로 정하면 「읽기 전에는 어느 달 기준을
     * 조회할지 모른다」는 순환에 빠지고, 현재 달로 대신하면 8월 말에 9월 배차표를 미리
     * 올릴 때 엉뚱한 달의 기준을 본다.
     */
    @PostMapping("/recognitions")
    @Operation(summary = "배차표 사진 인식 — 저장하지 않고 결과만 준다(검수용)")
    fun recognize(
        @RequestPart("file") file: MultipartFile,
        @RequestParam yearMonth: String,
    ): ResponseEntity<DataResponseBody<RecognitionResponse>> =
        ResponseEntity.ok(DataResponseBody(recognitionService.recognize(file.bytes, YearMonth.parse(yearMonth))))

    @PostMapping("/shifts")
    @Operation(summary = "검수 확정분 저장 — 보낸 날짜만 갱신한다")
    fun saveShifts(
        @Valid @RequestBody request: ShiftSaveRequest,
    ): ResponseEntity<Void> {
        commandService.saveShifts(request)
        return ResponseEntity.noContent().build()
    }
}
