package com.toy.backend.diet.food

import com.toy.backend.common.response.DataResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "식품DB", description = "식품 영양성분 검색 (항목 수정 화면용)")
@RestController
@RequestMapping("/diet/foods")
class FoodController(
    private val matcher: FoodMatcher,
) {
    @GetMapping
    @Operation(summary = "식품 검색 — 이름 부분일치, 짧은 이름 우선")
    fun search(
        @Parameter(description = "검색어", example = "제육")
        @RequestParam q: String,
        @Parameter(description = "최대 건수 (1~50)", example = "20")
        @RequestParam(defaultValue = "20") size: Int,
    ): ResponseEntity<DataResponseBody<List<FoodResponse>>> =
        ResponseEntity.ok(DataResponseBody(matcher.search(q, size).map { it.toResponse() }))
}
