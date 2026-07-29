package com.toy.backend.diet.profile

import com.toy.backend.common.response.DataResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "식단 프로필", description = "키·몸무게·활동량·목표와 계산된 목표 섭취량")
@RestController
@RequestMapping("/diet/profile")
class NutritionProfileController(
    private val service: NutritionProfileService,
) {
    @GetMapping
    @Operation(summary = "내 프로필 + 계산된 목표 조회")
    fun get(authentication: Authentication): ResponseEntity<DataResponseBody<NutritionProfileResponse>> =
        ResponseEntity.ok(DataResponseBody(service.get(authentication.name)))

    @PutMapping
    @Operation(summary = "프로필 저장 — 서버가 목표를 재계산한다")
    fun save(
        @Valid @RequestBody request: NutritionProfileRequest,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.save(authentication.name, request)
        return ResponseEntity.noContent().build()
    }

    @PutMapping("/weight")
    @Operation(summary = "몸무게만 갱신 — 매일 호출한다. 목표를 재계산한다")
    fun updateWeight(
        @Valid @RequestBody request: WeightUpdateRequest,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.updateWeight(authentication.name, request)
        return ResponseEntity.noContent().build()
    }
}
