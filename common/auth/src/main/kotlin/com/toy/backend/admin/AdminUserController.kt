package com.toy.backend.admin

import com.toy.backend.auth.AuthService
import com.toy.backend.auth.RegisterRequest
import com.toy.backend.common.response.DataResponseBody
import com.toy.backend.common.response.ErrorResponseBody
import com.toy.backend.user.UserResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "관리자", description = "관리자 API")
@RestController
@RequestMapping("/admin/users")
class AdminUserController(
    private val service: AdminUserService,
    private val authService: AuthService,
) {
    @PostMapping
    @Operation(summary = "사용자 등록")
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
    fun register(
        @Valid @RequestBody request: RegisterRequest,
    ): ResponseEntity<Long> = ResponseEntity.ok(authService.register(request))

    @GetMapping
    @Operation(summary = "사용자 목록 조회")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "성공"),
        ],
    )
    @PreAuthorize("hasAuthority('ADMIN')")
    fun list(authentication: Authentication): ResponseEntity<DataResponseBody<List<UserResponse>>> =
        ResponseEntity.ok(DataResponseBody(service.list(authentication.name)))

    @GetMapping("/{id}")
    @Operation(summary = "사용자 단건 조회")
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
    @PreAuthorize("hasAuthority('ADMIN')")
    fun get(
        @Parameter(description = "사용자 ID", example = "1")
        @PathVariable id: Long,
    ): ResponseEntity<DataResponseBody<UserResponse>> = ResponseEntity.ok(DataResponseBody(service.get(id)))

    @PutMapping("/{id}")
    @Operation(summary = "사용자 수정")
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
    @PreAuthorize("hasAuthority('ADMIN')")
    fun update(
        @Parameter(description = "사용자 ID", example = "1")
        @PathVariable id: Long,
        @Valid @RequestBody request: AdminUserUpdateRequest,
    ): ResponseEntity<Void> {
        service.update(id, request)
        return ResponseEntity.noContent().build()
    }

    @PatchMapping("/{id}/unlock")
    @Operation(summary = "계정 잠금 해제")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "잠금 해제됨"),
            ApiResponse(
                responseCode = "404",
                description = "찾을 수 없음",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    @PreAuthorize("hasAuthority('ADMIN')")
    fun unlock(
        @Parameter(description = "사용자 ID", example = "1")
        @PathVariable id: Long,
    ): ResponseEntity<Void> {
        service.unlock(id)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "사용자 삭제")
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
    @PreAuthorize("hasAuthority('ADMIN')")
    fun delete(
        @Parameter(description = "사용자 ID", example = "1")
        @PathVariable id: Long,
    ): ResponseEntity<Void> {
        service.delete(id)
        return ResponseEntity.noContent().build()
    }
}
