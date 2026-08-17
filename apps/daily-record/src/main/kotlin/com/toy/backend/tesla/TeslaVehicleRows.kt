/*
 * TeslaMate 조회의 **행 타입**들. 리포지토리가 돌려주고 서비스가 받는다.
 *
 * **여기 있는 시각은 전부 UTC다** — TeslaMate가 타임존 없는 `timestamp` 컬럼에 UTC 값을 넣기
 * 때문이다. KST로 되돌리는 것은 서비스의 일이고, KST를 담는 것은 `TeslaVehicleDtos.kt`의
 * 응답 타입이다. 두 파일을 갈라 둔 이유가 그 경계다.
 */

package com.toy.backend.tesla

import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.YearMonth

data class DriveMonthRow(
    val month: YearMonth,
    val count: Int,
    val distanceKm: BigDecimal?,
    val drivingMin: Int?,
)

data class PositionRow(
    val dateUtc: LocalDateTime,
    val latitude: BigDecimal?,
    val longitude: BigDecimal?,
    val batteryLevel: Int?,
    val usableBatteryLevel: Int?,
    val ratedRangeKm: BigDecimal?,
    val estRangeKm: BigDecimal?,
    val odometerKm: Double?,
    val insideTempC: BigDecimal?,
    val outsideTempC: BigDecimal?,
    val climateOn: Boolean?,
    val tpmsFl: BigDecimal?,
    val tpmsFr: BigDecimal?,
    val tpmsRl: BigDecimal?,
    val tpmsRr: BigDecimal?,
)

data class StateRow(
    /** `online`·`offline`·`asleep`. **번역하지 않는다** — 상류가 값을 늘리면 그대로 올라온다. */
    val state: String,
    val startDateUtc: LocalDateTime,
)

/**
 * TeslaMate는 `driving`·`charging`을 `states`에 **저장하지 않는다**
 * (`CREATE TYPE states_status AS ENUM ('online', 'offline', 'asleep')`).
 * 열린 행에서 파생시킨다.
 *
 * **「열린」것만으로는 부족하고 「최근」이어야 한다.** TeslaMate가 죽으면 마감되지 않은 세션이
 * 영원히 남는다 — 리포지토리 SQL이 24시간 창을 거는 이유다.
 */
data class ActivityRow(
    val charging: Boolean,
    val driving: Boolean,
)

data class GeofenceRow(
    val name: String,
    val latitude: BigDecimal,
    val longitude: BigDecimal,
    val radiusM: Int,
)

/**
 * 한 달치 배터리 열화 표본. **두 지표의 표본 조건이 달라 개수도 따로 낸다** —
 * 한 숫자로 합치면 `capacityKwh`가 null인 이유가 「표본이 없어서」인지 「값이 없어서」인지
 * 화면에서 갈리지 않는다.
 *
 * `fullRangeKm`은 non-null이다. 그 달에 행이 왔다는 것은 `end_battery_level >= 80`이고
 * `end_rated_range_km IS NOT NULL`인 충전이 최소 하나 있었다는 뜻이라, 중앙값이 null이 될 길이
 * WHERE에서 막혀 있다.
 */
data class BatteryHealthMonthRow(
    val month: YearMonth,
    val fullRangeKm: BigDecimal,
    /**
     * `end_battery_level >= 80`이고 `end_rated_range_km`이 있으면서 ΔSoC ≥ 40인 충전이
     * 그 달에 없으면 null이다. `percentile_cont`가 null 입력을 무시한 결과다.
     *
     * **앞의 두 조건은 용량이 쓰지도 않는 것인데 공통 WHERE에서 물려받는다** — 푸는 대가로
     * `fullRangeKm`의 non-null 보장이 깨진다. 자세한 근거는 `BatteryHealthSample.capacityKwh`.
     */
    val capacityKwh: BigDecimal?,
    val sampleCount: Int,
    val capacitySampleCount: Int,
)
