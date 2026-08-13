package com.toy.backend.tesla

import com.toy.backend.common.response.DataResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * TeslaMate DB를 직접 읽는다. TeslaMate에는 쓸 수 있는 데이터 API가 없다 —
 * `/api`에 있는 것은 로깅 resume/suspend 둘뿐이고, 금액 수정은 LiveView 화면으로만 제공된다.
 *
 * 인증은 기본 SecurityConfig가 요구한다. `PublicEndpoint`를 두지 않는다 —
 * 충전 시각·장소·금액은 생활 패턴이 그대로 드러나는 값이다.
 */
@Tag(name = "충전 내역", description = "TeslaMate 충전 내역 API")
@RestController
@RequestMapping("/tesla/charges")
class TeslaChargeController(
    private val service: TeslaChargeService,
) {
    @GetMapping("/{id}")
    @Operation(summary = "충전 상세 조회")
    fun detail(
        @PathVariable id: Long,
    ): ResponseEntity<DataResponseBody<TeslaChargeDetailResponse>> = ResponseEntity.ok(DataResponseBody(service.detail(id)))

    @PutMapping("/{id}/cost")
    @Operation(summary = "충전 금액 수정")
    fun updateCost(
        @PathVariable id: Long,
        @Valid @RequestBody request: ChargeCostRequest,
    ): ResponseEntity<Void> {
        service.updateCost(id, request)
        return ResponseEntity.noContent().build()
    }
}
