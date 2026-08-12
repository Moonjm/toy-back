package com.toy.backend.diet.meal

import com.toy.backend.common.response.DataResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "자주 먹는 음식", description = "내가 저장했던 항목을 빈도순으로")
@RestController
@RequestMapping("/diet/items")
class FrequentItemController(
    private val service: FrequentItemService,
) {
    @GetMapping("/frequent")
    @Operation(summary = "자주 먹는 음식 — 응답 한 건이 그대로 끼니 항목이 된다")
    fun frequent(
        @Parameter(description = "최근 며칠 (1~90)", example = "30")
        @RequestParam(defaultValue = "30") days: Int,
        @Parameter(description = "최대 건수 (1~50)", example = "20")
        @RequestParam(defaultValue = "20") size: Int,
        authentication: Authentication,
    ): ResponseEntity<DataResponseBody<List<FrequentItemResponse>>> =
        ResponseEntity.ok(DataResponseBody(service.list(authentication.name, days, size)))
}
