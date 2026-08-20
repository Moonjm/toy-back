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

    /** 요일별 주행 합. 요일 번호 규약은 `WeekdayDriveRow` 참조. 행이 온 요일만 온다. */
    fun weekdayDrives(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<WeekdayDriveRow>

    /** 요일별 충전 시간. 규칙은 `weekdayDrives`와 같다. */
    fun weekdayCharges(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<WeekdayChargeRow>

    /**
     * 요일·시각별 충전 시작 건수. **KST로 옮긴 뒤 뽑고 `weekday`는 0이 일요일**이다 —
     * `TeslaVehicleRepository.driveTimes`와 같은 규약이다. 0인 칸은 행이 오지 않는다.
     */
    fun chargeTimes(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<ChargeTimeRow>

    /** 최고 속도 버킷별 주행 건수. 행이 온 버킷만 온다. */
    fun speedBuckets(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<SpeedBucketRow>

    /**
     * 평균 속도 버킷별 거리·정격거리 소모. **나눗셈(`distance ÷ (duration_min ÷ 60)`)은 버킷만
     * 고르고 응답에 나가지 않아** 「나눗셈을 하지 않는다」에 안 걸린다.
     */
    fun speedEnergyBuckets(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<SpeedEnergyBucketRow>

    /** 충전 시작·종료 SoC 분포. 행이 온 버킷만 온다. */
    fun chargeLevelBuckets(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<ChargeLevelBucketRow>

    /**
     * 충전소별 합, 건수 많은 순 상위 10곳. 이름이 끝내 없는 충전은 세지 않는다.
     *
     * 모집단은 `/tesla/charges/totals`와 같다 — 케이블만 꽂았다 뺀 축퇴 세션을 뺀다.
     */
    fun chargers(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<ChargerRow>

    /** 주행 도착지 기준 지역 수. **행은 늘 온다**(`GROUP BY`가 없다). */
    fun regions(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): RegionRow

    /**
     * 역대 기록 셋(최장거리·최장시간·최고효율), 종류마다 최대 한 행.
     *
     * **파라미터가 없다 — `months` 범위를 따르지 않는다.** 범위가 바뀔 때마다 1등이 바뀌면
     * 기록이 아니다. `driveStats`의 `maxSpeedKmh`와 같은 규약이다.
     *
     * PostgreSQL은 `DESC`의 기본이 `NULLS FIRST`라 NULL 행이 1등으로 뽑힌다. 실측 NULL은
     * 0건이지만 `SPEED_BUCKETS_SQL`과 같은 이유로 구현이 명시로 걷고 `NULLS LAST`도 붙인다.
     */
    fun driveRecords(): List<DriveRecordRow>

    /**
     * 범위 안의 SOC 표본, 시각순. **5분 슬롯마다 하나로 솎아서 온다.**
     *
     * **이 인터페이스에서 `positions`를 읽는 유일한 자리다.** 범위가 있으므로 BRIN이
     * 듣는다(실측 48시간 32ms). 범위 없는 조회를 여기 더하지 마라 — 실측 11.7초다.
     */
    fun batterySamples(
        windowStartUtc: LocalDateTime,
        windowEndUtc: LocalDateTime,
    ): List<BatterySampleRow>

    /**
     * `sinceUtc` 이후 시작된 순수 주차 구간의 팬텀 드레인 합. **집계라 행은 늘 온다.**
     *
     * 「순수 주차」의 정의와 유령 충전 규칙은 `parkDrainMonthly`와 같다.
     */
    fun parkDrainSince(sinceUtc: LocalDateTime): ParkDrainRow
}
