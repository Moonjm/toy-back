package com.toy.backend.tesla

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
}
