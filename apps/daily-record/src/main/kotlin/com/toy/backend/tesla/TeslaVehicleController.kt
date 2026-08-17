package com.toy.backend.tesla

import com.toy.backend.common.response.DataResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.YearMonth

/**
 * 충전(`/tesla/charges` 하위)과 차량(`/tesla/summary`·`/tesla/status`·`/tesla/battery-health`·
 * `/tesla/drive-insights`)을 갈라 둔다 —
 * 읽는 테이블도 갱신 주기도 다르다. 한 파일에 다섯 엔드포인트를 두면 그 경계가 안 보인다.
 *
 * 인증은 기존 SecurityConfig가 요구한다. `PublicEndpoint`를 두지 않는다 —
 * 주행 거리·위치·차량 상태는 충전 시각보다 더 직접적으로 생활을 드러낸다.
 */
@Tag(name = "차량", description = "TeslaMate 차량 요약·상태·배터리 건강·주행 인사이트 API")
@RestController
@RequestMapping("/tesla")
class TeslaVehicleController(
    private val service: TeslaVehicleService,
) {
    @GetMapping("/summary")
    @Operation(summary = "월별 차량 요약 — 주행·충전 합계, 직전 달, 12개월 추이, 그 달의 충전 목록")
    fun summary(
        @Parameter(description = "조회 연월", example = "2026-08")
        @RequestParam(required = false)
        @DateTimeFormat(pattern = "yyyy-MM")
        yearMonth: YearMonth?,
    ): ResponseEntity<DataResponseBody<TeslaSummaryResponse>> = ResponseEntity.ok(DataResponseBody(service.summary(yearMonth)))

    @GetMapping("/status")
    @Operation(summary = "차량 현재 상태 — 값과 그 값의 기준 시각(asOf)을 함께 낸다")
    fun status(): ResponseEntity<DataResponseBody<TeslaStatusResponse>> = ResponseEntity.ok(DataResponseBody(service.status()))

    /**
     * 읽는 테이블은 `charging_processes`지만 **충전이 아니라 차량에 붙인다** —
     * 이 값이 답하는 질문은 「차가 어떤 상태인가」다.
     *
     * 파라미터가 없다. 전 기간을 내고, 몇 개월을 그릴지는 앱이 정한다.
     */
    @GetMapping("/battery-health")
    @Operation(summary = "월별 배터리 열화 표본 — 만충 환산 주행거리·사용 가능 용량의 중앙값과 표본 수")
    fun batteryHealth(): ResponseEntity<DataResponseBody<TeslaBatteryHealthResponse>> =
        ResponseEntity.ok(DataResponseBody(service.batteryHealth()))

    /**
     * **네 카드를 한 응답에 싣는다.** 나누면 같은 화면이 네 번 부르고 그중 셋은 나머지 하나를
     * 기다린다. `months`는 응답에 되돌려 실어 앱이 무엇을 받았는지 알 수 있게 한다.
     */
    @GetMapping("/drive-insights")
    @Operation(summary = "주행 인사이트 — 온도별 전비·주행 시간대·거리 분포·자주 가는 곳")
    fun driveInsights(
        @Parameter(description = "거슬러 볼 개월 수(1~60)", example = DEFAULT_MONTHS)
        @RequestParam(defaultValue = DEFAULT_MONTHS)
        months: Int,
    ): ResponseEntity<DataResponseBody<TeslaDriveInsightsResponse>> = ResponseEntity.ok(DataResponseBody(service.driveInsights(months)))
}

/** 애너테이션 인자라 컴파일 상수여야 해서 문자열이다. */
private const val DEFAULT_MONTHS = "12"
