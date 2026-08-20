/*
 * `/tesla/insights`·`/tesla/battery-window`의 행 타입들. 리포지토리가 돌려주고 서비스가
 * 받는다.
 *
 * **여기 있는 시각은 전부 UTC다** — TeslaMate가 타임존 없는 `timestamp` 컬럼에 UTC 값을 넣기
 * 때문이다. KST로 되돌리는 것은 서비스의 일이고, KST를 담는 것은 `TeslaInsightsDtos.kt`의
 * 응답 타입이다. `TeslaVehicleRows.kt`와 같은 경계다.
 */

package com.toy.backend.tesla

import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * 한 달치 주행 집계. `/tesla/summary`의 `DriveMonthRow`와 모집단·경계·반올림이 같고
 * `ratedRangeUsedKm` 하나가 더 있다 — 효율 추세의 분모 재료다. **두 응답의 같은 달 숫자가
 * 달라지면 앱의 어느 화면이 맞는지 알 수 없어진다.**
 *
 * 행이 온 달만 온다. 빈 달의 자리를 채우는 것은 서비스가 한다.
 */
data class InsightsDriveMonthRow(
    val month: YearMonth,
    val driveCount: Int,
    val distanceKm: BigDecimal,
    val drivingMin: Int?,
    /** `start_rated_range_km − end_rated_range_km`의 합. **음수 주행은 0으로 보고 더한다.** */
    val ratedRangeUsedKm: BigDecimal,
)

/**
 * 한 달치 충전 집계. `/tesla/summary`의 `ChargeMonthRow`에 `chargingMin`이 더 있다 —
 * 정지 시간의 뺄셈에 쓴다.
 *
 * `chargingMin` 말고는 전부 nullable이다. `cost`는 금액 미입력만 있는 달이면 `SUM`이
 * null이고, `energyAddedKwh`·`energyUsedKwh`는 에너지를 잃은 채 금액만 남은 세션(`id=15`
 * 계열, 실측 1건)만 있는 달이면 `SUM`이 null이다 — **0이 아니라 null이 사실이다.**
 */
data class InsightsChargeMonthRow(
    val month: YearMonth,
    val chargeCount: Int,
    val energyAddedKwh: BigDecimal?,
    val energyUsedKwh: BigDecimal?,
    val cost: BigDecimal?,
    val chargingMin: Int,
)

/**
 * 한 달치 팬텀 드레인. 연속한 두 주행 사이에 충전이 하나도 없는 구간만 세고, 그 구간의
 * `이전 주행 end_rated_range_km − 다음 주행 start_rated_range_km`를 더한 것이 `ratedKm`이다.
 *
 * **음수 구간을 0으로 자르지 않는다** — 충전 기록 없이 정격거리가 늘어난 구간이 실측으로
 * 3,960:628 섞여 있다(BMS 재보정 등). 자르면 합이 위로 편향된다(월 합은 실측 75~169km로
 * 어차피 양수다).
 *
 * `samples`를 함께 내는 이유: 표본 3건짜리 달과 90건짜리 달이 응답에서 같아 보이면 안 된다.
 */
data class ParkDrainMonthRow(
    val month: YearMonth,
    val ratedKm: BigDecimal,
    val samples: Int,
)

/**
 * 요일 하나의 주행 합. **`weekday`는 1이 월요일**(ISO)이다 — `DriveTimeRow`의 0=일요일
 * (PostgreSQL `dow`)과 다르다. 두 배열이 한 응답에 함께 나가 `InsightsWeekday`에도 적어 둔다.
 */
data class WeekdayDriveRow(
    val weekday: Int,
    val driveCount: Int,
    val distanceKm: BigDecimal,
    /** `SUM(duration_min)`이라 행이 있어도 null일 수 있다 — `InsightsDriveMonthRow.drivingMin`과 같다. */
    val drivingMin: Int?,
)

/** 요일 하나의 충전 시간. 정지 시간의 뺄셈에만 쓴다 — 그래서 건수·전력량이 없다. */
data class WeekdayChargeRow(
    val weekday: Int,
    val chargingMin: Int,
)

/**
 * 요일·시각별 충전 시작 건수. **`weekday`는 0이 일요일**(PostgreSQL `dow`)이다 —
 * `DriveTimeRow`와 같은 규약이고, 같은 히트맵 짝이라 맞춰야 한다.
 * (`WeekdayDriveRow`의 1=월요일과는 다르다.)
 */
data class ChargeTimeRow(
    val weekday: Int,
    val hour: Int,
    val count: Int,
)

/** 최고 속도 버킷 하나의 건수. `bucket`은 1..7이고 경계는 `TeslaBuckets.SPEED`가 갖는다. */
data class SpeedBucketRow(
    val bucket: Int,
    val driveCount: Int,
)

/**
 * 평균 속도 버킷 하나의 합. `bucket`은 1..5이고 경계는 `TeslaBuckets.SPEED_ENERGY`가 갖는다.
 *
 * **건수를 내지 않는다.** 이 배열이 답하는 것은 「빠르게 달릴수록 전비가 어떻게 되나」이고,
 * 그 답은 거리와 정격거리 소모 둘로 난다. 건수는 `speedBuckets`가 이미 낸다.
 */
data class SpeedEnergyBucketRow(
    val bucket: Int,
    val distanceKm: BigDecimal,
    val ratedRangeUsedKm: BigDecimal,
)

/**
 * 충전 SoC 버킷 하나. 시작과 종료를 한 행에 함께 낸다 — 같은 모집단 위의 두 분포라
 * 쿼리를 나누면 같은 테이블을 두 번 훑는다. `bucket`은 1..10이고 경계는
 * `TeslaBuckets.CHARGE_LEVEL`이 갖는다.
 *
 * **정확히 100%는 10번 칸이다.** `level / 10 + 1`이면 11번이 되어 어느 라벨에도 안 붙는데,
 * 실측으로 그런 충전이 71건 — 가장 흔한 종료 SoC가 통째로 사라진다.
 */
data class ChargeLevelBucketRow(
    val bucket: Int,
    val startCount: Int,
    val endCount: Int,
)

/** 충전소 하나의 합. 이름 COALESCE 순서 근거는 `CHARGERS_SQL` 참조. */
data class ChargerRow(
    val name: String,
    val chargeCount: Int,
    /** 에너지를 잃은 채 금액만 남은 세션(실측 1건)만 있는 충전소면 `SUM`이 null이다 — 0이 아니다. */
    val energyAddedKwh: BigDecimal?,
    /** 근거는 `Charger.cost` 참조. */
    val cost: BigDecimal?,
    /** 근거는 `Charger.costMissingCount` 참조. */
    val costMissingCount: Int,
)

/** 다녀온 지역 수. 행이 항상 오는 이유는 `TeslaInsightsRepository.regions` 참조. */
data class RegionRow(
    val cities: Int,
    val states: Int,
    val countries: Int,
)

/**
 * 기록 하나. 세 종류(`kind`: `distance`·`duration`·`efficiency`)가 같은 타입으로 오는 이유는
 * 뽑는 방법이 「`drives` 한 행」으로 같아서다. **`kind` 문자열은 `DRIVE_RECORDS_SQL`과 같아야
 * 하고 번역하지 않는다.**
 *
 * 실측(2026-08-20)으로 `drives`의 다섯 컬럼에 NULL이 0건이라 전부 non-null이다.
 */
data class DriveRecordRow(
    val kind: String,
    val driveId: Long,
    val startedAtUtc: LocalDateTime,
    val distanceKm: BigDecimal,
    val durationMin: Int,
    val ratedRangeUsedKm: BigDecimal,
)
