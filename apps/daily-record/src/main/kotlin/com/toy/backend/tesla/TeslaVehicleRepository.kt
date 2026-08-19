package com.toy.backend.tesla

import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * TeslaMate의 주행·위치·상태를 읽는다. **전부 읽기 전용이다** — 쓰는 것은
 * `TeslaChargeRepository.updateCost` 하나뿐이다.
 *
 * 행 타입의 시각은 전부 UTC다. KST 변환은 서비스가 한다.
 */
interface TeslaVehicleRepository {
    /**
     * 월별 주행 집계. **월 경계는 KST 기준**이고 진행 중인 주행(`end_date IS NULL`)은 제외한다 —
     * 끝나기 전에는 `distance`가 확정 전이다. 데이터가 없는 달은 행이 오지 않는다.
     */
    fun driveMonthly(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<DriveMonthRow>

    /**
     * 가장 최근 위치 1행. 없으면 null.
     *
     * `positions`는 3,000만 행이고 `date`에 BRIN만 있어, 창 없는 `ORDER BY date DESC`는
     * 실측 11.7초다. 7일 창(실측 123ms)을 먼저 돌리고 비면 PK 역순으로 폴백한다.
     */
    fun findLatestPosition(): PositionRow?

    /** `states`의 열린 행(`end_date IS NULL`). 없으면 null. */
    fun findOpenState(): StateRow?

    /**
     * **최근 24시간 안에 시작된** 열린 충전·주행 행의 존재 여부. `state` 파생에 쓴다.
     *
     * 최근성 조건이 핵심이다 — TeslaMate가 죽으면 마감되지 않은 세션이 영원히 남아,
     * 조건이 없으면 몇 년 전 유령 하나가 늘 「지금 충전 중」을 만든다.
     */
    fun findActivity(): ActivityRow

    /** 전부 읽는다 — 지오펜스는 몇 개 수준이라 반경 판정을 서버에서 한다. */
    fun findGeofences(): List<GeofenceRow>

    /**
     * 월별 배터리 열화 표본. **전 기간을 낸다** — 몇 년을 타도 월 행 수는 수십이고,
     * 열화는 시작점부터 봐야 의미가 있다. 오래된 달부터 온다.
     *
     * **표본이 없는 달은 행이 오지 않는다.** `driveMonthly`와 같다 — 자리를 채우는 것은
     * 여기서 하지 않고, 이쪽은 서비스도 채우지 않는다(설계: 없는 달은 없는 대로 둔다).
     *
     * 월 경계는 **`end_date` 기준 KST**다. 측정 시점은 충전이 끝난 때이고,
     * `start_date`로 자르면 자정을 넘긴 오버나이트 충전이 앞 달로 들어간다.
     */
    fun batteryHealthMonthly(): List<BatteryHealthMonthRow>

    /**
     * 온도 버킷별 주행 합. **자기 지표가 쓰는 조건만 건다** —
     * `outside_temp_avg IS NOT NULL`(어느 버킷에도 못 넣는다)과
     * `ΔratedRange > 0`(넣으면 전비가 무한대가 된다)이다.
     *
     * **행이 온 버킷만 온다.** 빈 버킷의 자리를 채우는 것은 서비스가 한다.
     */
    fun driveTemperatureBuckets(months: Int): List<DriveTemperatureBucketRow>

    /**
     * 요일·시각별 주행 건수. **KST로 옮긴 뒤 뽑는다** — UTC로 뽑으면 아침 8시 출근이
     * 밤 11시로 찍힌다. 0인 칸은 행이 오지 않는다(168칸 중 대부분이 0이다).
     *
     * 온도·주행가능거리 조건을 걸지 않는다. 시간대는 둘 다 쓰지 않는다.
     */
    fun driveTimes(months: Int): List<DriveTimeRow>

    /** 거리 버킷별 주행 합. 온도·주행가능거리 조건을 걸지 않는다. 행이 온 버킷만 온다. */
    fun driveDistanceBuckets(months: Int): List<DriveDistanceBucketRow>

    /**
     * 도착지별 주행 합, 건수 많은 순 상위 10개.
     *
     * 이름은 **지오펜스 → 주소** 순으로 떨어진다. 이 DB에는 지오펜스가 0행이지만 최근 12개월
     * 958건이 전부 도착지 주소를 갖고 있어(실측 2026-08-19) 목록이 채워진다.
     * 이름이 끝내 없는 도착지는 세지 않는다 — 실측으로 0건이다.
     */
    fun drivePlaces(months: Int): List<DrivePlaceRow>

    /**
     * `cars.efficiency`(kWh/km) 그대로. 차량이 1대라 파라미터가 없다.
     *
     * **null일 수 있다** — TeslaMate가 아직 못 채운 경우다. 그때 앱은 전비 카드를 감춘다.
     * 차량 상수라 기간 창을 걸지 않는다.
     */
    fun carEfficiency(): BigDecimal?
}
