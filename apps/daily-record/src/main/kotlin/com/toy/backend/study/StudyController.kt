package com.toy.backend.study

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "공부", description = "공부 타이머 API")
@RestController
@RequestMapping("/study")
class StudyController {
    @GetMapping("/subjects")
    @Operation(summary = "과목 목록 조회")
    fun subjects(): ResponseEntity<List<StudySubjectResponse>> =
        ResponseEntity.ok(StudySubject.entries.map { it.toResponse() })
}
