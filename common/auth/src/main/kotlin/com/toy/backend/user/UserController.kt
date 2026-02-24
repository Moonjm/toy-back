package com.toy.backend.user

import com.toy.backend.common.response.DataResponseBody
import com.toy.backend.common.response.ErrorResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "사용자", description = "사용자 API")
@RestController
@RequestMapping("/users")
class UserController(
    private val service: UserService,
) {
    @GetMapping
    @Operation(summary = "일반 사용자 목록 조회")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "성공"),
            ApiResponse(
                responseCode = "401",
                description = "인증 필요",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun members(): ResponseEntity<DataResponseBody<List<UserResponse>>> = ResponseEntity.ok(DataResponseBody(service.members()))

    @GetMapping("/me")
    @Operation(summary = "내 정보 조회")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "성공"),
            ApiResponse(
                responseCode = "401",
                description = "인증 필요",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "찾을 수 없음",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun me(authentication: Authentication): ResponseEntity<DataResponseBody<UserResponse>> =
        ResponseEntity.ok(DataResponseBody(service.me(authentication.name)))

    @PatchMapping("/me")
    @Operation(summary = "내 정보 수정")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "성공"),
            ApiResponse(
                responseCode = "400",
                description = "잘못된 요청",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 필요",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "찾을 수 없음",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun updateMe(
        @Valid @RequestBody request: UserUpdateRequest,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.updateMe(authentication.name, request)
        return ResponseEntity.noContent().build()
    }
}
