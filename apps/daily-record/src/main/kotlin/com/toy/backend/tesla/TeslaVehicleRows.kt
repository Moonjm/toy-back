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

/*
 * 아래 넷은 `/tesla/drive-insights`의 행 타입이다. **집계는 전부 SQL이 하고 여기 오는 것은
 * 이미 합쳐진 값이다** — 서비스가 하는 일은 버킷 자리를 채우고 DTO로 옮기는 것뿐이다.
 */

/**
 * 온도 버킷 하나의 합. `bucket`은 1..5이고 그 경계는 `TeslaVehicleService.TEMPERATURE_BUCKETS`가
 * 라벨로 갖는다 — **SQL의 `CASE`와 그 목록이 같은 숫자를 써야 한다.**
 *
 * `ratedRangeUsedKm`은 `start_rated_range_km - end_rated_range_km`의 합이다. kWh 환산
 * (`× cars.efficiency`)과 전비 나눗셈은 앱이 한다.
 */
data class DriveTemperatureBucketRow(
    val bucket: Int,
    val driveCount: Int,
    val distanceKm: BigDecimal,
    val ratedRangeUsedKm: BigDecimal,
)

/** `weekday`는 **0이 일요일**이다(PostgreSQL `dow` 그대로). KST 기준으로 뽑는다. */
data class DriveTimeRow(
    val weekday: Int,
    val hour: Int,
    val count: Int,
)

/**
 * 거리 버킷 하나의 합. `bucket`은 1..5이고 경계는
 * `TeslaVehicleService.DISTANCE_BUCKETS`가 갖는다.
 */
data class DriveDistanceBucketRow(
    val bucket: Int,
    val driveCount: Int,
    val distanceKm: BigDecimal,
)

/** 도착 지오펜스별 합. **주소는 내지 않는다** — 이름을 붙인 곳만 센다. */
data class DrivePlaceRow(
    val name: String,
    val driveCount: Int,
    val distanceKm: BigDecimal,
)

/*
 * 아래 셋은 `/tesla/state-timeline`과 주행 통계의 행 타입이다.
 */

/**
 * `states`의 한 구간. **창에 맞춰 이미 잘려서 온다** — 자르는 규칙을 서버 한 곳에만 두려는
 * 것이다(앱이 창 밖 값을 받아 스스로 자르면 규칙이 두 곳에 생긴다).
 *
 * `state`는 `online`·`offline`·`asleep`이다. `StateRow`와 같은 이유로 **번역하지 않는다**.
 */
data class StateSegmentRow(
    val state: String,
    val fromUtc: LocalDateTime,
    val toUtc: LocalDateTime,
)

/**
 * 주행·충전의 한 구간. 둘이 같은 모양이라 타입을 함께 쓴다 — 응답에서도 배열만 다르다.
 *
 * **마감되지 않은 유령 세션은 여기 오지 않는다.** 리포지토리 SQL의 24시간 창이 거른다
 * (`ActivityRow`와 같은 규칙이다). 진행 중인 진짜 세션은 `toUtc`가 창 끝으로 막혀 온다.
 */
data class SegmentRow(
    val fromUtc: LocalDateTime,
    val toUtc: LocalDateTime,
)

/**
 * 역대 최고 속도와 이번 달·올해 주행거리. **셋의 창이 서로 다르다** —
 * 최고 속도는 전 기간이고 거리 둘은 KST 월·연 경계다.
 *
 * `maxSpeedKmh`만 nullable이다. 주행이 하나도 없으면 「역대 최고」라는 값 자체가 없지만,
 * 거리 둘은 기간이 못박힌 합계라 그 기간에 안 탔으면 **0이 사실이다**.
 */
data class DriveStatsRow(
    val maxSpeedKmh: Int?,
    val monthDistanceKm: BigDecimal,
    val yearDistanceKm: BigDecimal,
)
