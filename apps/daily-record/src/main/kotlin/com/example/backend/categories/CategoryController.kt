package com.example.backend.categories

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
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "카테고리", description = "카테고리 API")
@RestController
@RequestMapping("/categories")
class CategoryController(
    private val service: CategoryService,
) {
    @GetMapping
    @Operation(summary = "카테고리 목록 조회")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "성공"),
        ],
    )
    fun list(
        @Parameter(description = "활성 여부 필터", example = "true")
        @RequestParam(required = false) active: Boolean?,
    ): ResponseEntity<DataResponseBody<List<CategoryResponse>>> = ResponseEntity.ok(DataResponseBody(service.list(active)))

    @GetMapping("/{id}")
    @Operation(summary = "카테고리 단건 조회")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "성공"),
            ApiResponse(
                responseCode = "404",
                description = "찾을 수 없음",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun get(
        @Parameter(description = "카테고리 ID", example = "1")
        @PathVariable id: Long,
    ): ResponseEntity<DataResponseBody<CategoryResponse>> = ResponseEntity.ok(DataResponseBody(service.get(id)))

    @PostMapping
    @ResponseCreated("/categories/{id}")
    @Operation(summary = "카테고리 생성")
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
        @Valid @RequestBody request: CategoryRequest,
    ): ResponseEntity<Long> = ResponseEntity.ok(service.create(request).id)

    @PutMapping("/{id}")
    @Operation(summary = "카테고리 수정")
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
        @Valid @RequestBody request: CategoryRequest,
    ): ResponseEntity<Void> {
        service.update(id, request)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "카테고리 삭제")
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
        @Parameter(description = "카테고리 ID", example = "1")
        @PathVariable id: Long,
    ): ResponseEntity<Void> {
        service.delete(id)
        return ResponseEntity.noContent().build()
    }

    @PutMapping("/order")
    @Operation(summary = "카테고리 순서 변경")
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
    fun reorder(
        @Valid @RequestBody request: CategoryMoveRequest,
    ): ResponseEntity<Void> {
        service.move(request)
        return ResponseEntity.noContent().build()
    }
}
