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
