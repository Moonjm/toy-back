package com.toy.backend.tesla

import com.toy.backend.common.response.DataResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * TeslaMate DB를 직접 읽는다. TeslaMate에는 쓸 수 있는 데이터 API가 없다 —
 * `/api`에 있는 것은 로깅 resume/suspend 둘뿐이고, 금액 수정은 LiveView 화면으로만 제공된다.
 *
 * 인증은 기본 SecurityConfig가 요구한다. `PublicEndpoint`를 두지 않는다 —
 * 충전 시각·장소·금액은 생활 패턴이 그대로 드러나는 값이다.
 */
@Tag(name = "충전 내역", description = "TeslaMate 충전 누적과 곡선 조회 API")
@RestController
@RequestMapping("/tesla/charges")
class TeslaChargeController(
    private val service: TeslaChargeService,
) {
    @GetMapping("/missing-cost")
    @Operation(summary = "금액이 빈 충전 조회 — 최근 한 달, 최신순")
    fun missingCost(
        @Parameter(description = "최대 건수 (1~200)", example = "50")
        @RequestParam(required = false, defaultValue = "50")
        limit: Int,
    ): ResponseEntity<DataResponseBody<MissingCostResponse>> = ResponseEntity.ok(DataResponseBody(service.missingCost(limit)))

    /**
     * 전 기간 누적. **파라미터가 없다.**
     *
     * `missing-cost`와 마찬가지로 `/{id}`보다 위에 둔다 — Spring은 리터럴 경로를 먼저 맞추므로
     * 동작은 순서와 무관하지만, 읽는 사람이 `totals`를 id로 오해하지 않게 한다.
     */
    @GetMapping("/totals")
    @Operation(summary = "충전 누적 — 전 기간 kWh·비용, 급속/완속 구분")
    fun totals(): ResponseEntity<DataResponseBody<TeslaChargeTotalsResponse>> = ResponseEntity.ok(DataResponseBody(service.totals()))

    @GetMapping("/{id}")
    @Operation(summary = "충전 상세 조회")
    fun detail(
        @PathVariable id: Long,
    ): ResponseEntity<DataResponseBody<TeslaChargeDetailResponse>> = ResponseEntity.ok(DataResponseBody(service.detail(id)))

    /**
     * 그 세션의 kW 곡선. **샘플을 줄이지 않는다** — 완속은 1,700개까지 간다.
     * 지난 기록이라 「실시간을 내지 않는다」는 방침과 부딪히지 않는다.
     */
    @GetMapping("/{id}/curve")
    @Operation(summary = "충전 곡선 — 그 세션의 kW·배터리 샘플")
    fun curve(
        @PathVariable id: Long,
    ): ResponseEntity<DataResponseBody<TeslaChargeCurveResponse>> = ResponseEntity.ok(DataResponseBody(service.curve(id)))

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
