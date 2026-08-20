package com.toy.backend.tesla

import java.math.BigDecimal
import java.time.LocalDateTime
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
    /** 요일별 합, **월요일(1)부터 일곱 개가 늘 온다.** 요일 번호 규약은 `InsightsWeekday` 참조. */
    val weekday: List<InsightsWeekday>,
    /** 충전 시작의 요일×시각. **0인 칸은 빠지고 `weekday`는 0이 일요일**이다(`driveTimes`와 같다). */
    val chargeTimes: List<DriveTime>,
    /** 일곱 개가 늘 온다. 주행 한 건의 **최고** 속도 분포다. */
    val speedBuckets: List<SpeedBucket>,
    /** 다섯 개가 늘 온다. 주행 한 건의 **평균** 속도별 전비 재료다. */
    val speedEnergyBuckets: List<SpeedEnergyBucket>,
    /** 열 개가 늘 온다. 충전을 **시작한** SoC 분포다. */
    val chargeStartLevels: List<ChargeLevelBucket>,
    /** 열 개가 늘 온다. 충전을 **끝낸** SoC 분포다. */
    val chargeEndLevels: List<ChargeLevelBucket>,
    /** 충전소 상위 10곳. 지오펜스가 없으면 주소로 떨어진다. 없으면 빈 배열이다. */
    val chargers: List<Charger>,
    /** 다녀온 지역 수. 주소가 없으면 셋 다 0이다 — null이 아니다. */
    val regions: Regions,
    /** 역대 기록 셋. 근거는 `TeslaInsightsRepository.driveRecords` 참조. */
    val records: InsightsRecords,
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

/**
 * 요일 하나의 합. **평균을 내지 않는다 — 분자(`driveCount`·`distanceKm`)와 분모
 * (`occurrences`)를 함께 낸다.** 이 저장소는 나눗셈을 앱에 맡긴다.
 */
data class InsightsWeekday(
    /** **1 = 월요일**(ISO). `driveTimes`의 0=일요일과 다르다. */
    val weekday: Int,
    val driveCount: Int,
    val distanceKm: BigDecimal,
    val drivingMin: Int,
    /**
     * 범위 안에서 그 요일이 며칠 나왔나 — **요일 평균의 분모다.** 오늘도 한 번으로 센다(오늘의
     * 주행이 이미 분자에 있어 짝이 맞는다) — `idleMin`의 분모는 반대로 흐른 분만 센다.
     */
    val occurrences: Int,
    /**
     * 정지 시간(분) = 그 요일에 **실제로 흐른 분** − 주행 − 충전. 0 미만은 0으로 자른다.
     *
     * `occurrences × 1440`이 아니다 — 오늘이 반쪽이라 그렇게 하면 오늘 요일만 정지 시간이
     * 최대 하루치 부풀어 오른다.
     */
    val idleMin: Int,
)

/** `toKmh`가 null이면 상한이 없다. 경계는 **`fromKmh` 포함, `toKmh` 미만**이다. */
data class SpeedBucket(
    val fromKmh: Int,
    val toKmh: Int?,
    val driveCount: Int,
)

/**
 * 평균 속도별 거리·정격거리 소모(전비 나눗셈은 앱이 한다). **`driveCount`가 없다** — 건수는
 * `speedBuckets`가 내고, 이쪽은 `ΔratedRange > 0`인 주행만 들어 모집단이 다르다.
 */
data class SpeedEnergyBucket(
    val fromKmh: Int,
    val toKmh: Int?,
    val distanceKm: BigDecimal,
    val ratedRangeUsedKm: BigDecimal,
)

/**
 * 충전 SoC 버킷. 경계는 `fromPct` 포함, `toPct` 미만인데 **마지막 칸(`90~100`)만
 * 양끝이 닫힌다** — 정확히 100%로 끝난 충전이 실측 71건이라, 「미만」으로 두면 가장 흔한
 * 값이 어느 칸에도 안 들어간다.
 */
data class ChargeLevelBucket(
    val fromPct: Int,
    val toPct: Int,
    val count: Int,
)

/**
 * 충전소 하나. **표시 이름으로 묶여 오므로 이 목록 안에서 `name`은 유일하다** —
 * 앱이 이름을 행 식별에 써도 안전하다. 좌표는 내지 않는다.
 */
data class Charger(
    val name: String,
    val chargeCount: Int,
    val energyAddedKwh: BigDecimal?,
    /** **실제로 낸 돈이다.** 그 충전소가 전부 금액 미입력이면 null이다 — 0이 아니다. */
    val cost: BigDecimal?,
    /**
     * 금액 미입력 건수. 앱이 「4건 금액 없음」을 적을 수 있어야 한다 —
     * 없으면 「충전소별 비용 TOP」 순위가 조용히 뒤집힌다.
     * `/tesla/charges/totals`가 같은 이유로 같은 필드를 낸다.
     */
    val costMissingCount: Int,
)

/** 주행 도착지 주소 기준 지역 수. 이 차량은 나라가 1이지만 필드를 둔다 — 앱이 감출지 정한다. */
data class Regions(
    val cities: Int,
    val states: Int,
    val countries: Int,
)

/**
 * 명예의 전당. **셋 다 null일 수 있다** — 주행이 하나도 없거나(전부 null), 20km 넘는 주행이
 * 없으면(`bestEfficiency`만 null) 「역대」라는 값 자체가 없다.
 *
 * 「최다 고도 상승」을 두지 않는다 — `positions.elevation`을 주행마다 훑어야 하는데 범위가
 * 없는 3,000만 행 스캔이고, 얻는 것이 타일 한 칸이라 값이 비용을 못 넘는다.
 */
data class InsightsRecords(
    val longestDistance: DistanceRecord?,
    val longestDuration: DurationRecord?,
    val bestEfficiency: EfficiencyRecord?,
)

/**
 * `driveId`를 싣는 이유: 앱이 나중에 그 주행 상세로 보내고 싶어질 자리다. 지금 앱에 주행 상세
 * 화면이 없어 쓰이지 않지만, 안 실으면 그때 계약을 또 고쳐야 한다. `DurationRecord`·
 * `EfficiencyRecord`도 같은 이유로 싣는다.
 */
data class DistanceRecord(
    val driveId: Long,
    /** 출발 시각(KST). */
    val startedAt: LocalDateTime,
    val distanceKm: BigDecimal,
)

data class DurationRecord(
    val driveId: Long,
    val startedAt: LocalDateTime,
    val durationMin: Int,
)

/**
 * **거리 하한 20km를 넘은 주행 중** 정격거리 대비 실주행이 가장 좋았던 것 — 없으면 실측으로
 * 0.2km 주행이 8.2배로 1등이 된다.
 *
 * 비율을 내지 않는다 — 분자(`distanceKm`)와 분모(`ratedRangeUsedKm`)를 준다. 앱이
 * `distanceKm ÷ ratedRangeUsedKm`로 낸다.
 */
data class EfficiencyRecord(
    val driveId: Long,
    val startedAt: LocalDateTime,
    val distanceKm: BigDecimal,
    val ratedRangeUsedKm: BigDecimal,
)
