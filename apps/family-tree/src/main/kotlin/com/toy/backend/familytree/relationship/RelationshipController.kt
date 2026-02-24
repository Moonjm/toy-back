package com.toy.backend.familytree.relationship

import com.toy.backend.common.response.ErrorResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "가계도", description = "가계도 API")
@RestController
@RequestMapping("/family-trees")
class RelationshipController(
    private val relationshipService: RelationshipService,
) {
    @PostMapping("/{id}/relationships/spouse")
    @Operation(summary = "배우자 관계 추가")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "201", description = "생성됨"),
            ApiResponse(
                responseCode = "400",
                description = "이미 배우자가 있음",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "403",
                description = "편집 권한 필요",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun addSpouse(
        @PathVariable id: Long,
        @Valid @RequestBody request: SpouseRequest,
        authentication: Authentication,
    ): ResponseEntity<Long> =
        ResponseEntity.status(HttpStatus.CREATED).body(relationshipService.addSpouse(authentication.name, id, request))

    @DeleteMapping("/{id}/relationships/spouse")
    @Operation(summary = "배우자 관계 삭제")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "삭제됨"),
            ApiResponse(
                responseCode = "403",
                description = "편집 권한 필요",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun removeSpouse(
        @PathVariable id: Long,
        @Valid @RequestBody request: SpouseRequest,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        relationshipService.removeSpouse(authentication.name, id, request)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/relationships/parent")
    @Operation(summary = "부모-자식 관계 추가")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "201", description = "생성됨"),
            ApiResponse(
                responseCode = "400",
                description = "부모 최대 2명 초과 / 순환 관계",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "403",
                description = "편집 권한 필요",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun addParentChild(
        @PathVariable id: Long,
        @Valid @RequestBody request: ParentChildRequest,
        authentication: Authentication,
    ): ResponseEntity<Long> =
        ResponseEntity.status(HttpStatus.CREATED).body(relationshipService.addParentChild(authentication.name, id, request))

    @DeleteMapping("/{id}/relationships/parent")
    @Operation(summary = "부모-자식 관계 삭제")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "삭제됨"),
            ApiResponse(
                responseCode = "403",
                description = "편집 권한 필요",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun removeParentChild(
        @PathVariable id: Long,
        @Valid @RequestBody request: ParentChildRequest,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        relationshipService.removeParentChild(authentication.name, id, request)
        return ResponseEntity.noContent().build()
    }
}
