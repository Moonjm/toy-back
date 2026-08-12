package com.toy.backend.diet.daily

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "식단 활동 에너지", description = "HealthKit 활동 에너지 upsert")
@RestController
@RequestMapping("/diet/activity")
class DailyActivityController(
    private val service: DailyActivityService,
) {
    @PutMapping
    @Operation(summary = "하루 활동 에너지 저장 (upsert)")
    fun upsert(
        @Valid @RequestBody request: ActivityUpsertRequest,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        service.upsert(authentication.name, request)
        return ResponseEntity.noContent().build()
    }
}
