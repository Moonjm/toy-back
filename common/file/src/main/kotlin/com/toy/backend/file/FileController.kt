package com.toy.backend.file

import com.toy.backend.common.annotation.ResponseCreated
import com.toy.backend.common.response.DataResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@Tag(name = "파일", description = "파일 업로드/조회 API")
@RestController
@RequestMapping("/files")
@ConditionalOnBean(FileService::class)
class FileController(
    private val fileService: FileService,
) {
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseCreated("/files/{id}")
    @Operation(summary = "파일 업로드")
    fun upload(
        @RequestParam("file") file: MultipartFile,
    ): ResponseEntity<Long> = ResponseEntity.ok(fileService.upload(file))

    @GetMapping("/{id}/url")
    @Operation(summary = "파일 Presigned URL 조회")
    fun getUrl(
        @PathVariable id: Long,
    ): ResponseEntity<DataResponseBody<String>> = ResponseEntity.ok(DataResponseBody(fileService.getPresignedUrl(id)))
}
