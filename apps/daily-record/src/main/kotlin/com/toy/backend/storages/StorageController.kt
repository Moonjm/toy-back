package com.toy.backend.storages

import com.toy.backend.common.annotation.ResponseCreated
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
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "보관함", description = "보관함 관리 API")
@RestController
@RequestMapping("/storages")
class StorageController(
    private val service: StorageService,
) {
    // ── 보관함 ──

    @GetMapping
    @Operation(summary = "보관함 목록 조회")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "성공")])
    fun listStorages(authentication: Authentication): ResponseEntity<DataResponseBody<List<StorageResponse>>> =
        ResponseEntity.ok(DataResponseBody(service.listStorages(authentication.name)))

    @PostMapping
    @ResponseCreated("/storages/{id}")
    @Operation(summary = "보관함 생성")
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
    fun createStorage(
        @Valid @RequestBody request: StorageCreateRequest,
        authentication: Authentication,
    ): ResponseEntity<Long> = ResponseEntity.ok(service.createStorage(authentication.name, request))

    @PutMapping("/{storageId}")
    @Operation(summary = "보관함 이름 수정")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "수정됨"),
            ApiResponse(
                responseCode = "404",
                description = "찾을 수 없음",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun updateStorage(
        @PathVariable storageId: Long,
        @Valid @RequestBody request: StorageUpdateRequest,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.updateStorage(authentication.name, storageId, request)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{storageId}")
    @Operation(summary = "보관함 삭제")
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
    fun deleteStorage(
        @Parameter(description = "보관함 ID", example = "1") @PathVariable storageId: Long,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.deleteStorage(authentication.name, storageId)
        return ResponseEntity.noContent().build()
    }

    // ── 구역 ──

    @PostMapping("/{storageId}/sections")
    @ResponseCreated("/storages/{storageId}/sections/{id}")
    @Operation(summary = "구역 추가")
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
    fun createSection(
        @PathVariable storageId: Long,
        @Valid @RequestBody request: SectionCreateRequest,
        authentication: Authentication,
    ): ResponseEntity<Long> = ResponseEntity.ok(service.createSection(authentication.name, storageId, request))

    @PutMapping("/{storageId}/sections/{sectionId}")
    @Operation(summary = "구역 이름 수정")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "수정됨"),
            ApiResponse(
                responseCode = "404",
                description = "찾을 수 없음",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun updateSection(
        @PathVariable storageId: Long,
        @PathVariable sectionId: Long,
        @Valid @RequestBody request: SectionUpdateRequest,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.updateSection(authentication.name, storageId, sectionId, request)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{storageId}/sections/{sectionId}")
    @Operation(summary = "구역 삭제")
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
    fun deleteSection(
        @PathVariable storageId: Long,
        @PathVariable sectionId: Long,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.deleteSection(authentication.name, storageId, sectionId)
        return ResponseEntity.noContent().build()
    }

    // ── 품목 ──

    @GetMapping("/{storageId}/items")
    @Operation(summary = "보관함 품목 조회")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "성공")])
    fun listItems(
        @PathVariable storageId: Long,
        authentication: Authentication,
    ): ResponseEntity<DataResponseBody<List<SectionResponse>>> =
        ResponseEntity.ok(DataResponseBody(service.listItems(authentication.name, storageId)))

    @PostMapping("/{storageId}/items")
    @ResponseCreated("/storages/{storageId}/items/{id}")
    @Operation(summary = "품목 추가")
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
    fun createItem(
        @PathVariable storageId: Long,
        @Valid @RequestBody request: ItemRequest,
        authentication: Authentication,
    ): ResponseEntity<Long> = ResponseEntity.ok(service.createItem(authentication.name, storageId, request))

    @PutMapping("/{storageId}/items/{itemId}")
    @Operation(summary = "품목 수정")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "수정됨"),
            ApiResponse(
                responseCode = "404",
                description = "찾을 수 없음",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun updateItem(
        @PathVariable storageId: Long,
        @PathVariable itemId: Long,
        @Valid @RequestBody request: ItemRequest,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.updateItem(authentication.name, storageId, itemId, request)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{storageId}/items/{itemId}")
    @Operation(summary = "품목 삭제")
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
    fun deleteItem(
        @PathVariable storageId: Long,
        @PathVariable itemId: Long,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.deleteItem(authentication.name, storageId, itemId)
        return ResponseEntity.noContent().build()
    }
}
