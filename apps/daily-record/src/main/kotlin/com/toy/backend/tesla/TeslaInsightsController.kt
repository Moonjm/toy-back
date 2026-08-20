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
}

/** 애너테이션 인자라 컴파일 상수여야 해서 문자열이다. */
private const val DEFAULT_MONTHS = "12"
