package com.toy.backend.maintenance

import com.toy.backend.common.response.DataResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@Tag(name = "관리비", description = "고지서 사진 인식 → 검수 → 확정 저장, 그리고 조회·추이")
@RestController
@RequestMapping("/maintenance")
class MaintenanceController(
    private val recognitionService: MaintenanceRecognitionService,
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
}
