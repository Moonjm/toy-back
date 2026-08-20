package com.toy.backend.tesla

import com.toy.backend.common.response.DataResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 앱 통계 탭이 쓰는 둘을 모아 둔다. 충전(`TeslaChargeController`)·차량
 * (`TeslaVehicleController`)과 가른 기준은 같다 — 읽는 테이블도 답하는 질문도 다르다.
 *
 * 인증은 기존 `SecurityConfig`가 요구한다. `PublicEndpoint`를 두지 않는다 —
 * 요일별 주행 습관은 충전 시각보다 더 직접적으로 생활을 드러낸다.
 */
@Tag(name = "차량 통계", description = "TeslaMate 주행·충전 통계와 배터리 범위 API")
@RestController
@RequestMapping("/tesla")
class TeslaInsightsController(
    private val service: TeslaInsightsService,
) {
    /**
     * 차트 스물 몇 장을 한 응답에 싣는다 — 나누면 화면 하나가 열 번 넘게 부른다.
     *
     * `months`를 응답에 되돌려 싣는다. **0은 전체 기간**이다.
     */
    @GetMapping("/insights")
    @Operation(summary = "주행·충전 통계 — 월별·요일별·시간대·버킷·기록 (0은 전체 기간)")
    fun insights(
        @Parameter(description = "거슬러 볼 개월 수(0=전체, 1~60)", example = DEFAULT_MONTHS)
        @RequestParam(defaultValue = DEFAULT_MONTHS)
        months: Int,
    ): ResponseEntity<DataResponseBody<TeslaInsightsResponse>> = ResponseEntity.ok(DataResponseBody(service.insights(months)))

    /**
     * 개요 화면의 충전 레벨 카드 하나를 채운다. **표본은 5분마다 하나로 솎여서 나간다** —
     * 실측으로 48시간이 12,517행이라 그대로 내면 응답이 750KB이고, 상한인 168시간에서는
     * 6MB가 된다.
     *
     * 앱은 이 응답을 캐시하지 않는다 — 「최근 48시간」이 계속 움직인다.
     */
    @GetMapping("/battery-window")
    @Operation(summary = "배터리 추이 — 최근 몇 시간의 SOC 표본·충전 구간·최근 7일 팬텀 드레인")
    fun batteryWindow(
        @Parameter(description = "거슬러 볼 시간(1~168)", example = DEFAULT_HOURS)
        @RequestParam(defaultValue = DEFAULT_HOURS)
        hours: Int,
    ): ResponseEntity<DataResponseBody<TeslaBatteryWindowResponse>> = ResponseEntity.ok(DataResponseBody(service.batteryWindow(hours)))
}

/** 애너테이션 인자라 컴파일 상수여야 해서 문자열이다. */
private const val DEFAULT_MONTHS = "12"
private const val DEFAULT_HOURS = "48"
