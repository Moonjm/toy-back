package com.toy.backend.maintenance

import com.toy.backend.common.annotation.ResponseCreated
import com.toy.backend.common.response.DataResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.time.YearMonth

@Tag(name = "관리비", description = "고지서 사진 인식 → 검수 → 확정 저장, 그리고 조회·추이")
@RestController
@RequestMapping("/maintenance")
class MaintenanceController(
    private val recognitionService: MaintenanceRecognitionService,
    private val billService: MaintenanceBillService,
) {
    /**
     * **무인증으로 열지 않는다.** 응답에 동·호가 들어간다.
     *
     * 사진은 저장하지 않는다 — `common/file`의 `FileEntity`에 소유자가 없는 미해결 이슈를
     * 건드리지 않는다(AGENTS.md).
     */
    @PostMapping("/recognitions")
    @Operation(summary = "고지서 사진 인식 — 저장하지 않고 결과만 준다(검수용)")
    fun recognize(
        @RequestPart("file") file: MultipartFile,
    ): ResponseEntity<DataResponseBody<RecognitionResponse>> =
        ResponseEntity.ok(DataResponseBody(recognitionService.recognize(file.bytes, file.contentType)))

    /**
     * **`{id}` 자리에 연월이 들어간다.** `ResponseCreatedAspect`가 반환 본문을 그 자리에
     * 끼워 넣는데, 이 리소스의 주소는 DB id가 아니라 연월이다(`GET /maintenance/bills/2026-03`).
     */
    @PostMapping("/bills")
    @ResponseCreated("/maintenance/bills/{id}")
    @Operation(summary = "검수 확정분 저장 — 같은 달이 있으면 409")
    fun createBill(
        @Valid @RequestBody request: BillSaveRequest,
    ): ResponseEntity<String> = ResponseEntity.ok(billService.create(request))

    /**
     * **`YearMonth`로 받아 Spring이 변환하게 둔다.** 본문에서 `YearMonth.parse`를 부르면
     * 오타 하나가 `DateTimeParseException`이 되어 공통 핸들러의 500으로 떨어진다.
     */
    @GetMapping("/bills/{yearMonth}")
    @Operation(summary = "한 달 관리비 상세")
    fun findBill(
        @PathVariable yearMonth: YearMonth,
    ): ResponseEntity<DataResponseBody<BillResponse>> = ResponseEntity.ok(DataResponseBody(billService.findOne(yearMonth)))

    @GetMapping("/bills")
    @Operation(summary = "관리비 목록 — 최근 달부터")
    fun findBills(): ResponseEntity<DataResponseBody<BillListResponse>> = ResponseEntity.ok(DataResponseBody(billService.findAll()))

    @PutMapping("/bills/{yearMonth}")
    @Operation(summary = "한 달 관리비 수정 — 항목을 통째로 갈아 끼운다")
    fun replaceBill(
        @PathVariable yearMonth: YearMonth,
        @Valid @RequestBody request: BillSaveRequest,
    ): ResponseEntity<Void> {
        billService.replace(yearMonth, request)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/bills/{yearMonth}")
    @Operation(summary = "한 달 관리비 삭제")
    fun deleteBill(
        @PathVariable yearMonth: YearMonth,
    ): ResponseEntity<Void> {
        billService.delete(yearMonth)
        return ResponseEntity.noContent().build()
    }
}
