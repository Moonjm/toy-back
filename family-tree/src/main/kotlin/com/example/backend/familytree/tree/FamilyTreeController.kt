package com.example.backend.familytree.tree

import com.example.backend.common.annotation.ResponseCreated
import com.example.backend.common.response.DataResponseBody
import com.example.backend.common.response.ErrorResponseBody
import io.swagger.v3.oas.annotations.Operation
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

@Tag(name = "가계도", description = "가계도 API")
@RestController
@RequestMapping("/family-trees")
class FamilyTreeController(
    private val service: FamilyTreeService,
) {
    @PostMapping
    @ResponseCreated("/family-trees/{id}")
    @Operation(summary = "가계도 생성")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "201", description = "생성됨"),
        ],
    )
    fun create(
        @Valid @RequestBody request: FamilyTreeRequest,
        authentication: Authentication,
    ): ResponseEntity<Long> = ResponseEntity.ok(service.create(authentication.name, request))

    @GetMapping
    @Operation(summary = "내 가계도 목록 조회")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "성공"),
        ],
    )
    fun list(authentication: Authentication): ResponseEntity<DataResponseBody<List<FamilyTreeListResponse>>> =
        ResponseEntity.ok(DataResponseBody(service.list(authentication.name)))

    @GetMapping("/{id}")
    @Operation(summary = "가계도 상세 조회")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "성공"),
            ApiResponse(
                responseCode = "403",
                description = "접근 권한 없음",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun getDetail(
        @PathVariable id: Long,
        authentication: Authentication,
    ): ResponseEntity<DataResponseBody<FamilyTreeDetailResponse>> =
        ResponseEntity.ok(DataResponseBody(service.getDetail(authentication.name, id)))

    @PutMapping("/{id}")
    @Operation(summary = "가계도 수정")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "수정됨"),
            ApiResponse(
                responseCode = "403",
                description = "소유자만 가능",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun update(
        @PathVariable id: Long,
        @Valid @RequestBody request: FamilyTreeRequest,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.update(authentication.name, id, request)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "가계도 삭제")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "삭제됨"),
            ApiResponse(
                responseCode = "403",
                description = "소유자만 가능",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun delete(
        @PathVariable id: Long,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.delete(authentication.name, id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/members")
    @ResponseCreated("/family-trees/{id}/members/{memberId}")
    @Operation(summary = "멤버 추가")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "201", description = "생성됨"),
            ApiResponse(
                responseCode = "403",
                description = "소유자만 가능",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun addMember(
        @PathVariable id: Long,
        @Valid @RequestBody request: MemberRequest,
        authentication: Authentication,
    ): ResponseEntity<Long> = ResponseEntity.ok(service.addMember(authentication.name, id, request))

    @GetMapping("/{id}/members")
    @Operation(summary = "멤버 목록 조회")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "성공"),
        ],
    )
    fun listMembers(
        @PathVariable id: Long,
        authentication: Authentication,
    ): ResponseEntity<DataResponseBody<List<MemberResponse>>> =
        ResponseEntity.ok(DataResponseBody(service.listMembers(authentication.name, id)))

    @PutMapping("/{id}/members/{memberId}")
    @Operation(summary = "멤버 역할 변경")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "수정됨"),
            ApiResponse(
                responseCode = "403",
                description = "소유자만 가능",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun updateMemberRole(
        @PathVariable id: Long,
        @PathVariable memberId: Long,
        @Valid @RequestBody request: MemberRoleRequest,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.updateMemberRole(authentication.name, id, memberId, request)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{id}/members/{memberId}")
    @Operation(summary = "멤버 제거")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "삭제됨"),
            ApiResponse(
                responseCode = "403",
                description = "소유자만 가능",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "마지막 소유자 제거 불가",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun removeMember(
        @PathVariable id: Long,
        @PathVariable memberId: Long,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.removeMember(authentication.name, id, memberId)
        return ResponseEntity.noContent().build()
    }
}
