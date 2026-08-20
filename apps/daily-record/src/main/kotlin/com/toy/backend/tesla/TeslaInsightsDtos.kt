package com.toy.backend.tesla

import java.math.BigDecimal
import java.time.YearMonth

/**
 * 앱 통계 탭 한 장을 한 응답으로 채운다 — 나누면 화면 하나가 열 번 넘게 부른다.
 * 나눗셈은 앱이 한다(분자와 분모를 따로 낸다).
 *
 * `/tesla/drive-insights`가 내던 여덟 필드를 이름까지 그대로 싣는다 — 앱이 매핑 없이 옮겨 쓴다.
 * 그 여덟의 하위 타입(`TemperatureBucket`·`DriveTime`·`DistanceBucket`·`DrivePlace`)은
 * `TeslaVehicleDtos`의 것을 그대로 쓴다 — 같은 값을 두 이름으로 내면 앱이 둘 다 알아야 한다.
 */
data class TeslaInsightsResponse(
    /** 받은 범위를 되돌려 싣는다. **0은 전체 기간**이다. */
    val months: Int,
    /**
     * 범위의 오래된 달부터 이번 달까지, **기록이 없는 달도 자리를 지킨다**(0과 null은 다르다).
     *
     * `/tesla/summary`의 `trend`와 겹치지만 없애지 않는다 — `trend`는 12개월 고정이고
     * 이쪽은 기간 칩을 따른다. 앱은 개요·충전 탭에서 `trend`를, 통계 탭에서 이것을 본다.
     */
    val monthly: List<InsightsMonth>,
    /** `cars.efficiency` 그대로(kWh/km). null이면 앱이 전비 카드를 감춘다. */
    val efficiencyKwhPerKm: BigDecimal?,
    /** 다섯 개가 늘 온다. 빈 버킷도 자리를 지킨다. */
    val temperatureBuckets: List<TemperatureBucket>,
    /** **0인 칸은 빠진다.** `weekday`는 0이 일요일이다(PostgreSQL `dow` 그대로). */
    val driveTimes: List<DriveTime>,
    /** 다섯 개가 늘 온다. */
    val distanceBuckets: List<DistanceBucket>,
    /** 도착지 상위 10곳. 지오펜스가 없으면 주소로 떨어진다. 없으면 빈 배열이다. */
    val places: List<DrivePlace>,
    /** 역대 최고 속도(km/h). **`months`를 따르지 않는다** — 범위마다 바뀌면 기록이 아니다. */
    val maxSpeedKmh: Int?,
    /** 전 기간 총 주행거리(km). **`months`를 따르지 않는다.** 0을 낸다, null이 아니다. */
    val totalDistanceKm: BigDecimal,
    /** 주행 기록이 있는 달 수 — 평균의 분모다. **0으로 올 수 있다.** */
    val recordedMonths: Int,
)

/**
 * 한 달치 통계. **기록이 없는 필드는 0이 아니라 null이다** — 0은 「안 탔다」가 되어
 * 「기록이 없다」와 구분이 안 된다(`MonthlyStat`과 같은 규칙).
 *
 * 예외가 셋 있다. `idleMin`·`parkDrainRatedKm`·`parkDrainSamples`는 기록이 없어도 값이 온다 —
 * 정지 시간은 기록 없음이 곧 「내내 서 있었다」이고, 팬텀 드레인은 표본 수(0)가 이미
 * 「표본 없음」을 말하기 때문이다.
 */
data class InsightsMonth(
    val yearMonth: YearMonth,
    val distanceKm: BigDecimal?,
    val driveCount: Int?,
    val drivingMin: Int?,
    val energyAddedKwh: BigDecimal?,
    val energyUsedKwh: BigDecimal?,
    val cost: BigDecimal?,
    val chargeCount: Int?,
    val chargingMin: Int?,
    /** 효율 추세의 분모 재료. kWh 환산(`× efficiencyKwhPerKm`)과 나눗셈은 앱이 한다. */
    val ratedRangeUsedKm: BigDecimal?,
    /**
     * 정지 시간(분) = 그 달의 경과 분(`TeslaTime.monthElapsedMinutes`) − 주행 분 − 충전 분,
     * 0 미만은 0으로 자른다.
     *
     * **`states`를 읽지 않는다** — 이 차량의 `states`는 신뢰가 낮다(최근 7일 `offline` 131시간,
     * `asleep` 0개). 빼기로 내면 「차가 얼마나 서 있었나」에 정확히 답한다.
     */
    val idleMin: Int,
    /** 주차 구간 정격거리 하락 합(km). **음수 구간도 부호 그대로 들어 있다.** */
    val parkDrainRatedKm: BigDecimal,
    /** 위 합이 몇 구간에서 나왔나. **0이면 앱이 막대를 안 그린다.** */
    val parkDrainSamples: Int,
)
