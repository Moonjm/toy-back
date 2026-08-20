package com.toy.backend.tesla

import java.time.LocalDateTime
import java.time.YearMonth

/**
 * 앱 통계 화면의 집계를 읽는다. 읽기 전용이고 `drives`·`charging_processes`·`addresses`만 본다
 * (`positions`는 `/tesla/battery-window`만 읽는다).
 *
 * **범위는 서비스가 UTC로 계산해 넘긴다** — 「전체 기간」을 SQL로 표현할 길이 없어 `months`를
 * 받으면 그 분기가 쿼리 수만큼 복제된다.
 *
 * `TeslaVehicleRepository`와 갈라 둔 기준은 답하는 질문이다 — 저쪽은 「차가 어떤 상태인가」,
 * 이쪽은 「지난 N개월을 어떻게 탔나」다.
 */
interface TeslaInsightsRepository {
    /**
     * 가장 오래된 **완료** 주행의 달(KST). 주행이 하나도 없으면 null.
     *
     * `months=0`(전체 기간)의 시작을 정하는 데만 쓴다. `drives` 5천 행 전체 스캔이라
     * 실측 3ms다.
     */
    fun firstDriveMonth(): YearMonth?

    /**
     * 월별 주행 집계. **월 경계는 `start_date` 기준 KST**다 — `/tesla/summary`와 같은
     * 모집단·경계를 쓴다. 기록이 없는 달은 행이 오지 않는다.
     */
    fun driveMonthly(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<InsightsDriveMonthRow>

    /** 월별 충전 집계. 경계 규칙은 `driveMonthly`와 같다. */
    fun chargeMonthly(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<InsightsChargeMonthRow>

    /**
     * 월별 팬텀 드레인. **연속한 두 주행 사이에 충전이 없는 구간**만 센다.
     *
     * 구간은 앞 주행이 끝난 달로 친다. 자정을 걸친 주차는 앞 달로 들어가는데, 그 편이
     * 「그 달에 세운 차가 얼마나 샜나」에 가깝다.
     */
    fun parkDrainMonthly(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<ParkDrainMonthRow>
}
