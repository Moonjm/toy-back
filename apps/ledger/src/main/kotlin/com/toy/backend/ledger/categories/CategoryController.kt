package com.toy.backend.ledger.categories

import com.toy.backend.common.annotation.ResponseCreated
import com.toy.backend.common.response.DataResponseBody
import io.swagger.v3.oas.annotations.Operation
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

@Tag(name = "가계부 분류", description = "사용자별 지출 분류 관리")
@RestController
@RequestMapping("/categories")
class CategoryController(
    private val service: CategoryService,
) {
    @GetMapping
    @Operation(summary = "분류 목록 (이름순)")
    fun list(authentication: Authentication): ResponseEntity<DataResponseBody<List<CategoryResponse>>> =
        ResponseEntity.ok(DataResponseBody(service.list(authentication.name)))

    @PostMapping
    @ResponseCreated("/categories/{id}")
    @Operation(summary = "분류 생성")
    fun create(
        @Valid @RequestBody request: CategoryRequest,
        authentication: Authentication,
    ): ResponseEntity<Long> = ResponseEntity.ok(service.create(authentication.name, request))

    @PutMapping("/{id}")
    @Operation(summary = "분류 이름 변경")
    fun update(
        @PathVariable id: Long,
        @Valid @RequestBody request: CategoryRequest,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.update(authentication.name, id, request)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "분류 삭제 — 내역이 참조 중이면 409")
    fun delete(
        @PathVariable id: Long,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.delete(authentication.name, id)
        return ResponseEntity.noContent().build()
    }
}
