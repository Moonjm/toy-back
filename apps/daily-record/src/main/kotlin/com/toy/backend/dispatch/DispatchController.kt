package com.toy.backend.dispatch

import com.toy.backend.common.response.DataResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
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
    /**
     * **무인증으로 열린 단 하나의 엔드포인트다.** 응답에 실명·차량번호가 없다.
     *
     * **기간이 아니라 연월 하나를 받는다.** 임의의 `from`·`to`를 인증 없이 받으면 요청
     * 한 번으로 수백만 건을 만들어 힙이 터진다. 달력은 월 단위로만 보므로 범위를
     * 구조적으로 한 달에 가둔다. 형식이 틀리면 Spring 변환이 실패해 400이 나간다.
     */
    @GetMapping("/shifts")
    @Operation(summary = "월 근무 조회 — 아빠(확정분)와 엄마(패턴)를 합쳐 반환")
    fun findShifts(
        @RequestParam yearMonth: YearMonth,
    ): ResponseEntity<DataResponseBody<ShiftRangeResponse>> = ResponseEntity.ok(DataResponseBody(queryService.findMonth(yearMonth)))

    /**
     * **`yearMonth`는 선택이다.** 배차표 사진 위에 연월이 적혀 있고 모델이 그것을 읽으므로,
     * 앱은 보내지 않는다. 자리를 남겨 두는 것은 웹처럼 어느 달인지 이미 아는 호출자를 위해서다.
     * 보내면 그 값이 기준이 되고 사진 제목은 교차 확인용으로만 쓰인다.
     *
     * **`YearMonth`로 받아 Spring이 변환하게 둔다.** 본문에서 `YearMonth.parse`를 부르면
     * 오타 하나에 `DateTimeParseException`이 공통 핸들러의 500으로 떨어져 서버 결함처럼 보인다.
     */
    @PostMapping("/recognitions")
    @Operation(summary = "배차표 사진 인식 — 저장하지 않고 결과만 준다(검수용)")
    fun recognize(
        @RequestPart("file") file: MultipartFile,
        @RequestParam(required = false) yearMonth: YearMonth?,
    ): ResponseEntity<DataResponseBody<RecognitionResponse>> =
        ResponseEntity.ok(DataResponseBody(recognitionService.recognize(file.bytes, yearMonth)))

    @PostMapping("/shifts")
    @Operation(summary = "검수 확정분 저장 — 보낸 날짜만 갱신한다")
    fun saveShifts(
        @Valid @RequestBody request: ShiftSaveRequest,
    ): ResponseEntity<Void> {
        commandService.saveShifts(request)
        return ResponseEntity.noContent().build()
    }

    /**
     * **`POST /dispatch/shifts`를 확장하지 않고 새로 낸다.** 그쪽은 역할이 하나라 두 사람을
     * 고치려면 두 번 호출해야 하고, 그 사이에 실패하면 아빠만 저장된 상태가 남는다. 또 하나의
     * 문으로 「한 달 통째 확정」과 「하루 손수정」이 같이 들어오면 검증 규칙이 섞인다 —
     * 전자는 `days`가 비면 안 되고, 후자는 역할이 하나만 와도 된다.
     *
     * **`LocalDate`로 받아 Spring이 변환하게 둔다.** 본문에서 `LocalDate.parse`를 부르면 오타
     * 하나가 `DateTimeParseException`이 되어 공통 핸들러의 500으로 떨어진다(`recognize`와 같은 이유다).
     *
     * **무인증 목록(`DispatchPublicEndpoints`)에 넣지 않는다.** 조회를 공개한 근거는 응답에
     * 실명·차량번호가 없다는 것이지 아무나 고쳐도 된다는 뜻이 아니다.
     */
    @PutMapping("/shifts/{date}")
    @Operation(summary = "하루 근무 수정 — 아빠·엄마를 한 트랜잭션으로 갱신한다")
    fun editDay(
        @PathVariable date: LocalDate,
        @Valid @RequestBody request: DayEditRequest,
    ): ResponseEntity<Void> {
        commandService.editDay(date, request)
        return ResponseEntity.noContent().build()
    }
}
