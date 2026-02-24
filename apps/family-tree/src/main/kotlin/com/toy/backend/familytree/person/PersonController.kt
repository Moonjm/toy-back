package com.toy.backend.familytree.person

import com.toy.backend.common.annotation.ResponseCreated
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
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "가계도", description = "가계도 API")
@RestController
@RequestMapping("/family-trees")
class PersonController(
    private val personService: PersonService,
) {
    @PostMapping("/{id}/persons")
    @ResponseCreated("/family-trees/{id}/persons/{personId}")
    @Operation(summary = "인물 추가")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "201", description = "생성됨"),
            ApiResponse(
                responseCode = "403",
                description = "편집 권한 필요",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun addPerson(
        @PathVariable id: Long,
        @Valid @RequestBody request: PersonRequest,
        authentication: Authentication,
    ): ResponseEntity<Long> = ResponseEntity.status(HttpStatus.CREATED).body(personService.add(authentication.name, id, request))

    @PutMapping("/{id}/persons/{personId}")
    @Operation(summary = "인물 수정")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "수정됨"),
            ApiResponse(
                responseCode = "403",
                description = "편집 권한 필요",
                content = [Content(schema = Schema(implementation = ErrorResponseBody::class))],
            ),
        ],
    )
    fun updatePerson(
        @PathVariable id: Long,
        @PathVariable personId: Long,
        @Valid @RequestBody request: PersonRequest,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        personService.update(authentication.name, id, personId, request)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{id}/persons/{personId}")
    @Operation(summary = "인물 삭제")
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
    fun deletePerson(
        @PathVariable id: Long,
        @PathVariable personId: Long,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        personService.delete(authentication.name, id, personId)
        return ResponseEntity.noContent().build()
    }
}
