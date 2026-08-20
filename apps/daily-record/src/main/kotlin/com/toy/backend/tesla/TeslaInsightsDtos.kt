@file:Suppress("ktlint:standard:filename")

package com.toy.backend.tesla

import java.math.BigDecimal

/**
 * 앱 통계 탭 한 장을 한 응답으로 채운다 — 나누면 화면 하나가 열 번 넘게 부른다.
 * 나눗셈은 앱이 한다(분자와 분모를 따로 낸다).
 *
 * `/tesla/drive-insights`가 내던 여덟 필드를 이름까지 그대로 싣는다 — 앱이 매핑 없이 옮겨 쓴다.
 */
data class TeslaInsightsResponse(
    /** 받은 범위를 되돌려 싣는다. **0은 전체 기간**이다. */
    val months: Int,
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
