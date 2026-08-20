package com.toy.backend.tesla

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * nullable 정수는 `getObject`로 읽는다 — `rs.getInt`는 SQL NULL에 0을 준다
 * (`JdbcTeslaVehicleRepository`와 같은 규칙).
 *
 * 모든 SQL이 `:start`·`:end`를 **UTC**로 받는다. KST를 넘기면 9시간이 어긋난다.
 */
@Repository
class JdbcTeslaInsightsRepository(
    @Qualifier("teslaMateJdbcClient") private val teslaMateJdbcClient: JdbcClient,
) : TeslaInsightsRepository {
    override fun firstDriveMonth(): YearMonth? =
        teslaMateJdbcClient
            .sql(FIRST_DRIVE_MONTH_SQL)
            .query { rs, _ -> rs.getObject("month_start", LocalDate::class.java)?.let { YearMonth.from(it) } }
            .optional()
            .orElse(null)

    override fun driveMonthly(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<InsightsDriveMonthRow> =
        teslaMateJdbcClient
            .sql(DRIVE_MONTHLY_SQL)
            .param("start", startUtc)
            .param("end", endUtcExclusive)
            .query { rs, _ ->
                InsightsDriveMonthRow(
                    month = YearMonth.from(rs.getObject("month_start", LocalDate::class.java)),
                    driveCount = rs.getInt("drive_count"),
                    distanceKm = rs.getBigDecimal("distance_km"),
                    drivingMin = rs.nullableInt("driving_min"),
                    ratedRangeUsedKm = rs.getBigDecimal("rated_range_used_km"),
                )
            }.list()

    override fun chargeMonthly(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<InsightsChargeMonthRow> =
        teslaMateJdbcClient
            .sql(CHARGE_MONTHLY_SQL)
            .param("start", startUtc)
            .param("end", endUtcExclusive)
            .query { rs, _ ->
                InsightsChargeMonthRow(
                    month = YearMonth.from(rs.getObject("month_start", LocalDate::class.java)),
                    chargeCount = rs.getInt("charge_count"),
                    energyAddedKwh = rs.getBigDecimal("energy_added_kwh"),
                    energyUsedKwh = rs.getBigDecimal("energy_used_kwh"),
                    cost = rs.getBigDecimal("cost"),
                    chargingMin = rs.getInt("charging_min"),
                )
            }.list()

    override fun parkDrainMonthly(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<ParkDrainMonthRow> =
        teslaMateJdbcClient
            .sql(PARK_DRAIN_MONTHLY_SQL)
            .param("start", startUtc)
            .param("end", endUtcExclusive)
            .query { rs, _ ->
                ParkDrainMonthRow(
                    month = YearMonth.from(rs.getObject("month_start", LocalDate::class.java)),
                    ratedKm = rs.getBigDecimal("park_drain_rated_km") ?: BigDecimal.ZERO,
                    samples = rs.getInt("park_drain_samples"),
                )
            }.list()

    override fun weekdayDrives(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<WeekdayDriveRow> =
        teslaMateJdbcClient
            .sql(WEEKDAY_DRIVES_SQL)
            .param("start", startUtc)
            .param("end", endUtcExclusive)
            .query { rs, _ ->
                WeekdayDriveRow(
                    weekday = rs.getInt("weekday"),
                    driveCount = rs.getInt("drive_count"),
                    distanceKm = rs.getBigDecimal("distance_km"),
                    drivingMin = rs.nullableInt("driving_min"),
                )
            }.list()

    override fun weekdayCharges(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<WeekdayChargeRow> =
        teslaMateJdbcClient
            .sql(WEEKDAY_CHARGES_SQL)
            .param("start", startUtc)
            .param("end", endUtcExclusive)
            .query { rs, _ ->
                WeekdayChargeRow(
                    weekday = rs.getInt("weekday"),
                    chargingMin = rs.getInt("charging_min"),
                )
            }.list()

    override fun chargeTimes(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<ChargeTimeRow> =
        teslaMateJdbcClient
            .sql(CHARGE_TIMES_SQL)
            .param("start", startUtc)
            .param("end", endUtcExclusive)
            .query { rs, _ ->
                ChargeTimeRow(rs.getInt("weekday"), rs.getInt("hour"), rs.getInt("row_count"))
            }.list()

    override fun speedBuckets(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<SpeedBucketRow> =
        teslaMateJdbcClient
            .sql(SPEED_BUCKETS_SQL)
            .param("start", startUtc)
            .param("end", endUtcExclusive)
            .query { rs, _ -> SpeedBucketRow(rs.getInt("bucket"), rs.getInt("drive_count")) }
            .list()

    override fun speedEnergyBuckets(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<SpeedEnergyBucketRow> =
        teslaMateJdbcClient
            .sql(SPEED_ENERGY_BUCKETS_SQL)
            .param("start", startUtc)
            .param("end", endUtcExclusive)
            .query { rs, _ ->
                SpeedEnergyBucketRow(
                    bucket = rs.getInt("bucket"),
                    distanceKm = rs.getBigDecimal("distance_km"),
                    ratedRangeUsedKm = rs.getBigDecimal("rated_range_used_km"),
                )
            }.list()

    override fun chargeLevelBuckets(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<ChargeLevelBucketRow> =
        teslaMateJdbcClient
            .sql(CHARGE_LEVEL_BUCKETS_SQL)
            .param("start", startUtc)
            .param("end", endUtcExclusive)
            .query { rs, _ ->
                ChargeLevelBucketRow(
                    bucket = rs.getInt("bucket"),
                    startCount = rs.getInt("start_count"),
                    endCount = rs.getInt("end_count"),
                )
            }.list()

    override fun chargers(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<ChargerRow> =
        teslaMateJdbcClient
            .sql(CHARGERS_SQL)
            .param("start", startUtc)
            .param("end", endUtcExclusive)
            .query { rs, _ ->
                ChargerRow(
                    name = rs.getString("name"),
                    chargeCount = rs.getInt("charge_count"),
                    energyAddedKwh = rs.getBigDecimal("energy_added_kwh"),
                    cost = rs.getBigDecimal("cost"),
                    costMissingCount = rs.getInt("cost_missing_count"),
                )
            }.list()

    override fun regions(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): RegionRow =
        teslaMateJdbcClient
            .sql(REGIONS_SQL)
            .param("start", startUtc)
            .param("end", endUtcExclusive)
            .query { rs, _ ->
                RegionRow(rs.getInt("cities"), rs.getInt("states"), rs.getInt("countries"))
            }.single()

    override fun driveRecords(): List<DriveRecordRow> =
        teslaMateJdbcClient
            .sql(DRIVE_RECORDS_SQL)
            .query { rs, _ ->
                DriveRecordRow(
                    kind = rs.getString("kind"),
                    driveId = rs.getLong("drive_id"),
                    startedAtUtc = rs.getObject("started_at", LocalDateTime::class.java),
                    distanceKm = rs.getBigDecimal("distance_km"),
                    durationMin = rs.getInt("duration_min"),
                    ratedRangeUsedKm = rs.getBigDecimal("rated_range_used_km"),
                )
            }.list()

    override fun batterySamples(
        windowStartUtc: LocalDateTime,
        windowEndUtc: LocalDateTime,
    ): List<BatterySampleRow> =
        teslaMateJdbcClient
            .sql(BATTERY_SAMPLES_SQL)
            .param("start", windowStartUtc)
            .param("end", windowEndUtc)
            .query { rs, _ ->
                BatterySampleRow(
                    dateUtc = rs.getObject("date", LocalDateTime::class.java),
                    batteryLevel = rs.getInt("battery_level"),
                    usableBatteryLevel = rs.nullableInt("usable_battery_level"),
                )
            }.list()

    override fun parkDrainSince(sinceUtc: LocalDateTime): ParkDrainRow =
        teslaMateJdbcClient
            .sql(PARK_DRAIN_SINCE_SQL)
            .param("since", sinceUtc)
            .query { rs, _ ->
                ParkDrainRow(
                    ratedKm = rs.getBigDecimal("rated_km") ?: BigDecimal.ZERO,
                    hours = rs.getBigDecimal("hours") ?: BigDecimal.ZERO,
                    samples = rs.getInt("samples"),
                )
            }.single()

    private fun ResultSet.nullableInt(column: String): Int? = getObject(column) as Int?

    companion object {
        /**
         * **KST로 자른다.** UTC로 자르면 KST 9월 1일 새벽에 시작한 첫 주행이 8월로 잡혀
         * 전체 기간이 한 달 길어진다.
         *
         * `MIN`이라 `GROUP BY`가 없어 행은 늘 오지만, `drives`가 비면 `month_start`가 null이다.
         */
        private const val FIRST_DRIVE_MONTH_SQL = """
            SELECT date_trunc(
                       'month',
                       MIN(d.start_date) AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Seoul'
                   )::date AS month_start
              FROM drives d
             WHERE d.end_date IS NOT NULL
        """

        /**
         * `/tesla/summary`의 `DRIVE_MONTHLY_SQL`과 모집단·경계·반올림이 같다 — 범위가
         * 파라미터로 오고 `rated_range_used_km`이 더 있다는 것만 다르다.
         *
         * **`GREATEST(..., 0)`으로 음수 주행을 0으로 자른다** — 회생·BMS 재보정으로 정격거리가
         * 늘어난 채 끝난 주행이 있는데, 효율 추세의 분모라 음수가 섞이면 그 달만 전비가 튄다.
         * 팬텀 드레인(`ParkDrainMonthRow`)은 반대로 자르지 않는다.
         */
        private const val DRIVE_MONTHLY_SQL = """
            SELECT date_trunc(
                       'month',
                       d.start_date AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Seoul'
                   )::date                            AS month_start,
                   COUNT(*)                           AS drive_count,
                   ROUND(SUM(d.distance)::numeric, 1) AS distance_km,
                   SUM(d.duration_min)::int           AS driving_min,
                   ROUND(SUM(GREATEST(d.start_rated_range_km - d.end_rated_range_km, 0)), 1)
                                                      AS rated_range_used_km
              FROM drives d
             WHERE d.end_date IS NOT NULL
               AND d.start_date >= :start
               AND d.start_date <  :end
             GROUP BY month_start
             ORDER BY month_start
        """

        /**
         * `/tesla/summary`의 `CHARGE_MONTHLY_SQL`과 같은 모집단이다 — 축퇴 세션을 걸러 내지
         * 않는 것도 그대로다(걸러 내면 두 화면의 충전 건수가 달라진다). `cost`의 `SUM`은
         * null을 건너뛰므로 실제로 낸 돈이 된다.
         *
         * `cost`는 `ROUND(..., 0)`으로 낸다 — `/tesla/summary`는 반올림하지 않지만 원화에 소수
         * 단위가 없고 실측 439건이 전부 정수라 값은 같다(`/tesla/charges/totals`도 같은 꼴이다).
         *
         * **`duration_min`은 `COALESCE`로 0을 채운다** — 정지 시간의 뺄셈에서 null이 전체를
         * null로 만들면 안 되고, 실측으로 최근 6개월에 null이 0건이다.
         */
        private const val CHARGE_MONTHLY_SQL = """
            SELECT date_trunc(
                       'month',
                       cp.start_date AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Seoul'
                   )::date                                AS month_start,
                   COUNT(*)                               AS charge_count,
                   ROUND(SUM(cp.charge_energy_added), 1)  AS energy_added_kwh,
                   ROUND(SUM(cp.charge_energy_used), 1)   AS energy_used_kwh,
                   ROUND(SUM(cp.cost), 0)                 AS cost,
                   COALESCE(SUM(cp.duration_min), 0)::int AS charging_min
              FROM charging_processes cp
             WHERE cp.end_date IS NOT NULL
               AND cp.start_date >= :start
               AND cp.start_date <  :end
             GROUP BY month_start
             ORDER BY month_start
        """

        /**
         * 월별 팬텀 드레인. 연속한 두 주행 사이에 충전이 하나도 없는 구간의 정격거리 하락을
         * 더한다.
         *
         * 급소 1 — `LEAD`를 범위 밖까지 포함해 돌린다. CTE에 범위 조건을 넣고 `LEAD`를 걸면
         * 범위 첫 주행의 직전 주차가 이웃을 못 봐 통째로 빠진다. 범위는 `LEAD` 뒤의 `WHERE`에서
         * 건다. `drives`가 5천 행이라 전체를 훑어도 실측 20ms다.
         *
         * **급소 2 — 겹침 판정에 `c.end_date IS NOT NULL`이 반드시 있어야 한다.** 마감되지
         * 않은 충전(이 DB에 2021~2025년 6건)을 `COALESCE`로 「지금까지 열려 있음」으로 보면
         * 그 뒤 모든 주차 구간이 「충전이 낀 구간」이 된다 — 실측으로 표본이 4,587건에서 76건
         * 으로 무너졌다. `/tesla/state-timeline`의 `SEGMENT_NOT_GHOST`와 같은 계열이다.
         *
         * `BETWEEN`이 아니라 겹침으로 본다 — 주차 시작 전에 시작해 주차 중에 끝난 충전을
         * `BETWEEN c.start_date`로는 못 잡는다(실측 차이 전 기간 1건, 4,588 → 4,587). 음수를
         * 자르지 않는 근거는 `ParkDrainMonthRow`의 KDoc.
         *
         * **급소 3 — 유령 주행(`end_date IS NULL`)을 `LEAD` 앞이 아니라 뒤에서 걸러야 한다.**
         * CTE의 `WHERE d.end_date IS NOT NULL`로 미리 빼면 유령의 앞뒤 완료 주행이 `LEAD`에서
         * 서로 이웃이 되어 그 사이 전체가 「순수 주차」로 잡힌다 — 실제로는 그 안에 주행이
         * 통째로 들어 있다. 실측으로 이렇게 잘못 잡힌 구간이 10건이고, 최악은 2023-07-30
         * 「주차」 1.6시간에 106.3km가 샌 값이었다(실제 팬텀 드레인은 하루 1~2km 수준이다).
         * 월별 합이 최대 +51%(2022-11, 301.6 → 200.0km) 부풀었고 전 기간 합은
         * 10,869.4 → 10,470.0km로 바뀐다. 고치려면 `LEAD`는 전체 주행(유령 포함) 위에서 돌리고,
         * 바깥 `WHERE`에서 `from_date`·`to_date`·`to_end_date` 셋 다 `NOT NULL`을 요구한다 —
         * `from_date`는 곧 `d.end_date`라 앞이 유령이면 저절로 걸러지고, `to_end_date`는 뒤가
         * 유령인 짝(유령 12건 전부 `start_rated_range_km`가 NULL이라 안 막으면 표본은 세도
         * `SUM`이 그 건을 건너뛴다)을 막으려고 따로 `LEAD`한 값이다.
         */
        private const val PARK_DRAIN_MONTHLY_SQL = """
            WITH park AS (
                SELECT d.end_date                                                       AS from_date,
                       LEAD(d.start_date)           OVER w                              AS to_date,
                       LEAD(d.end_date)             OVER w                              AS to_end_date,
                       d.end_rated_range_km - LEAD(d.start_rated_range_km) OVER w       AS drop_km
                  FROM drives d
                WINDOW w AS (ORDER BY d.start_date)
            )
            SELECT date_trunc(
                       'month',
                       p.from_date AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Seoul'
                   )::date               AS month_start,
                   COUNT(*)              AS park_drain_samples,
                   ROUND(SUM(p.drop_km), 1) AS park_drain_rated_km
              FROM park p
             WHERE p.from_date    IS NOT NULL
               AND p.to_date      IS NOT NULL
               AND p.to_end_date  IS NOT NULL
               AND p.from_date >= :start
               AND p.from_date <  :end
               AND NOT EXISTS (SELECT 1
                                 FROM charging_processes c
                                WHERE c.end_date IS NOT NULL
                                  AND c.start_date < p.to_date
                                  AND c.end_date   > p.from_date)
             GROUP BY month_start
             ORDER BY month_start
        """

        /**
         * **KST로 옮긴 뒤 뽑는다** — UTC로 뽑으면 월요일 아침 출근이 일요일 밤으로 찍힌다.
         * `isodow`라 1이 월요일이다(요일 번호 규약은 `WeekdayDriveRow` 참조). 경계는 `start_date`
         * 기준이라 자정을 걸친 주행은 출발한 요일로 친다.
         */
        private const val WEEKDAY_DRIVES_SQL = """
            SELECT EXTRACT(isodow FROM d.start_date AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Seoul')::int AS weekday,
                   COUNT(*)                                                                            AS drive_count,
                   ROUND(SUM(d.distance)::numeric, 1)                                                  AS distance_km,
                   SUM(d.duration_min)::int                                                            AS driving_min
              FROM drives d
             WHERE d.end_date IS NOT NULL
               AND d.start_date >= :start
               AND d.start_date <  :end
             GROUP BY weekday
             ORDER BY weekday
        """

        /**
         * **시작 요일에 몰지 않고 KST 자정으로 쪼개 넣는다.** 실측으로 전 기간 충전 485건 중
         * 310건(64%)이 자정을 걸친다 — 토요일 23시에 꽂아 일요일 07시에 빼면 8시간 전부가
         * 토요일로 잡혀 `idleMin`에서 토요일이 그만큼 줄고 일요일이 그만큼 는다(최근 12개월
         * 기준 일요일 `idleMin`이 약 12% 과대).
         *
         * 모집단은 그대로다 — `cp.start_date >= :start AND < :end`, `end_date IS NOT NULL`도
         * 안 바뀐다. **어느 충전을 세는지는 그대로고, 그 시간을 어느 요일에 넣는지만 바뀐다.**
         * `duration_min` 대신 `(end_kst − start_kst)`를 쓰는데, 쪼개려면 시각이 필요해서다 —
         * 실측 484건 전부 둘의 차이가 1분 이내(최대 0.5분)라 안전하다. `generate_series`의
         * 끝이 정확히 자정이면 0분짜리 칸이 하나 더 생기지만 합에 0을 더할 뿐이라 무해하다.
         */
        private const val WEEKDAY_CHARGES_SQL = """
            WITH span AS (
                SELECT (cp.start_date AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Seoul') AS from_kst,
                       (cp.end_date   AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Seoul') AS to_kst
                  FROM charging_processes cp
                 WHERE cp.end_date IS NOT NULL
                   AND cp.start_date >= :start
                   AND cp.start_date <  :end
            )
            SELECT EXTRACT(isodow FROM day)::int AS weekday,
                   COALESCE(ROUND(SUM(
                       EXTRACT(epoch FROM (LEAST(span.to_kst, day + interval '1 day')
                                         - GREATEST(span.from_kst, day))) / 60.0
                   )), 0)::int AS charging_min
              FROM span,
                   generate_series(date_trunc('day', span.from_kst),
                                   date_trunc('day', span.to_kst),
                                   interval '1 day') AS day
             GROUP BY weekday
             ORDER BY weekday
        """

        private const val CHARGE_TIMES_SQL = """
            SELECT EXTRACT(dow  FROM cp.start_date AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Seoul')::int AS weekday,
                   EXTRACT(hour FROM cp.start_date AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Seoul')::int AS hour,
                   COUNT(*)                                                                           AS row_count
              FROM charging_processes cp
             WHERE cp.end_date IS NOT NULL
               AND cp.start_date >= :start
               AND cp.start_date <  :end
             GROUP BY weekday, hour
             ORDER BY weekday, hour
        """

        /**
         * 버킷 경계는 `TeslaBuckets.SPEED`와 같은 숫자여야 한다. `ELSE 7`이 「120 이상」을
         * 받는데, 실측(2026-08-20)으로 `speed_max` 140 이상 주행이 0건이라 사실상 `120~140`이다.
         *
         * **`speed_max IS NOT NULL`을 명시로 건다** — 실측 NULL 0건이지만, 없으면 상류가 값을
         * 못 채우는 날 NULL이 조용히 `ELSE 7`(최고 속도 칸)로 들어간다.
         */
        private const val SPEED_BUCKETS_SQL = """
            SELECT CASE WHEN d.speed_max <  20 THEN 1
                        WHEN d.speed_max <  40 THEN 2
                        WHEN d.speed_max <  60 THEN 3
                        WHEN d.speed_max <  80 THEN 4
                        WHEN d.speed_max < 100 THEN 5
                        WHEN d.speed_max < 120 THEN 6
                        ELSE 7
                   END      AS bucket,
                   COUNT(*) AS drive_count
              FROM drives d
             WHERE d.end_date IS NOT NULL
               AND d.distance > 0
               AND d.speed_max IS NOT NULL
               AND d.start_date >= :start
               AND d.start_date <  :end
             GROUP BY bucket
             ORDER BY bucket
        """

        /**
         * **버킷 경계는 `TeslaBuckets.SPEED_ENERGY`와 같은 숫자여야 한다.**
         *
         * 평균 속도를 세 번 쓰지 않으려고 `avg_speed`를 CTE에서 한 번만 만든다.
         * `duration_min > 0`이 분모를 막고, `ΔratedRange > 0`은 전비가 무한대가 되는 주행을
         * 뺀다 — `DRIVE_TEMPERATURE_BUCKETS_SQL`이 같은 이유로 같은 조건을 건다.
         */
        private const val SPEED_ENERGY_BUCKETS_SQL = """
            WITH avg_speed AS (
                SELECT d.distance,
                       d.start_rated_range_km - d.end_rated_range_km AS rated_used,
                       d.distance / (d.duration_min / 60.0)          AS avg_speed
                  FROM drives d
                 WHERE d.end_date IS NOT NULL
                   AND d.distance > 0
                   AND d.duration_min > 0
                   AND d.start_rated_range_km - d.end_rated_range_km > 0
                   AND d.start_date >= :start
                   AND d.start_date <  :end
            )
            SELECT CASE WHEN avg_speed.avg_speed < 20 THEN 1
                        WHEN avg_speed.avg_speed < 40 THEN 2
                        WHEN avg_speed.avg_speed < 60 THEN 3
                        WHEN avg_speed.avg_speed < 80 THEN 4
                        ELSE 5
                   END                                        AS bucket,
                   ROUND(SUM(avg_speed.distance)::numeric, 1) AS distance_km,
                   ROUND(SUM(avg_speed.rated_used), 1)        AS rated_range_used_km
              FROM avg_speed
             GROUP BY bucket
             ORDER BY bucket
        """

        /**
         * **`LEAST(level / 10, 9) + 1`이 급소다.** `level / 10 + 1`이면 정확히 100%가 11번
         * 칸이 되어 `TeslaBuckets.CHARGE_LEVEL`의 어느 라벨에도 안 붙는다 —
         * 실측(2026-08-20)으로 그런 충전이 71건이고 종료 SoC 중 가장 흔한 값이다.
         *
         * 시작과 종료를 `UNION ALL`로 한 번에 뽑는다 — 같은 모집단 위의 두 분포라 쿼리를
         * 나누면 같은 테이블을 두 번 훑는다. `start_battery_level`·`end_battery_level`이
         * null인 충전이 실측 1건 있어 각각 자기 쪽에서만 뺀다 — 공통 WHERE로 올리면 한쪽
         * 지표의 조건이 다른 쪽 표본을 깎는다.
         */
        private const val CHARGE_LEVEL_BUCKETS_SQL = """
            SELECT b.bucket,
                   COUNT(*) FILTER (WHERE b.kind = 's') AS start_count,
                   COUNT(*) FILTER (WHERE b.kind = 'e') AS end_count
              FROM (
                    SELECT 's' AS kind, LEAST(cp.start_battery_level / 10, 9) + 1 AS bucket
                      FROM charging_processes cp
                     WHERE cp.end_date IS NOT NULL
                       AND cp.start_battery_level IS NOT NULL
                       AND cp.start_date >= :start
                       AND cp.start_date <  :end
                    UNION ALL
                    SELECT 'e', LEAST(cp.end_battery_level / 10, 9) + 1
                      FROM charging_processes cp
                     WHERE cp.end_date IS NOT NULL
                       AND cp.end_battery_level IS NOT NULL
                       AND cp.start_date >= :start
                       AND cp.start_date <  :end
                   ) b
             GROUP BY b.bucket
             ORDER BY b.bucket
        """

        /**
         * 모집단 근거는 `TeslaInsightsRepository.chargers` 참조.
         *
         * **COALESCE 순서는 `DRIVE_PLACES_SQL`과 같다** — 표시 이름으로 묶어야 재지오코딩으로
         * 갈린 행이 하나로 합쳐진다. `cost_missing_count` 근거는 `Charger.costMissingCount`
         * 참조. `ORDER BY` 마지막 열이 이름인 것은 `LIMIT 10` 경계의 tie-breaker다.
         */
        private const val CHARGERS_SQL = """
            SELECT COALESCE(g.name, a.name, a.road, a.city, a.display_name) AS name,
                   COUNT(*)                               AS charge_count,
                   ROUND(SUM(cp.charge_energy_added), 1)  AS energy_added_kwh,
                   ROUND(SUM(cp.cost), 0)                 AS cost,
                   COUNT(*) FILTER (WHERE cp.cost IS NULL) AS cost_missing_count
              FROM charging_processes cp
              LEFT JOIN geofences g ON g.id = cp.geofence_id
              LEFT JOIN addresses a ON a.id = cp.address_id
             WHERE cp.end_date IS NOT NULL
               AND (cp.charge_energy_added > 0 OR cp.cost IS NOT NULL)
               AND COALESCE(g.name, a.name, a.road, a.city, a.display_name) IS NOT NULL
               AND cp.start_date >= :start
               AND cp.start_date <  :end
             GROUP BY 1
             ORDER BY charge_count DESC, energy_added_kwh DESC, name
             LIMIT 10
        """

        /**
         * 주행 **도착지**의 주소로 센다. 출발지를 함께 세지 않는 이유는 어느 출발지든 직전
         * 주행의 도착지라 두 번 세게 되기 때문이다. 행이 늘 오는 근거는
         * `TeslaInsightsRepository.regions` 참조.
         *
         * `COUNT(DISTINCT ...)`가 null을 건너뛰므로 주소가 하나도 없으면 셋 다 0이다.
         * 실측(2026-08-20) 최근 12개월 도시 21·주 5·나라 1.
         */
        private const val REGIONS_SQL = """
            SELECT COUNT(DISTINCT a.city)    AS cities,
                   COUNT(DISTINCT a.state)   AS states,
                   COUNT(DISTINCT a.country) AS countries
              FROM drives d
              JOIN addresses a ON a.id = d.end_address_id
             WHERE d.end_date IS NOT NULL
               AND d.distance > 0
               AND d.start_date >= :start
               AND d.start_date <  :end
        """

        /**
         * 역대 기록 셋을 `UNION ALL`로 한 번에 낸다. `months`를 따르지 않는다.
         *
         * 급소 1 — 최고 효율에 거리 하한 20km가 있어야 한다. 없으면 실측으로 0.2km 주행이
         * 8.2배로 1등이 된다 — 정격거리 표시가 움직이지 않을 만큼 짧은 주행이다. 20km면
         * 1등이 26.7km/15.3km(1.74배)로 말이 된다.
         *
         * **급소 2 — `ORDER BY`마다 `id`를 tie-breaker로 둔다.** 실측으로 최장거리와 최장시간이
         * 같은 주행(id 3619)이고, 동률이 나올 때 어느 것이 뽑힐지 실행마다 달라지면 안 된다.
         *
         * **급소 3 — PostgreSQL은 `DESC`의 기본이 `NULLS FIRST`라 NULL 행이 1등으로 뽑힌다.**
         * 실측 NULL은 0건이지만 `SPEED_BUCKETS_SQL`과 같은 이유로 CTE에서 명시로 걷고
         * `NULLS LAST`도 붙인다.
         *
         * `ROUND`의 자릿수는 다른 집계와 맞춘다 — 거리는 소수 한 자리다.
         */
        private const val DRIVE_RECORDS_SQL = """
            WITH d AS (
                SELECT id, start_date, distance, duration_min,
                       start_rated_range_km - end_rated_range_km AS rated_used
                  FROM drives
                 WHERE end_date IS NOT NULL
                   AND distance IS NOT NULL
                   AND duration_min IS NOT NULL
                   AND start_rated_range_km - end_rated_range_km IS NOT NULL
            )
            (SELECT 'distance' AS kind, id AS drive_id, start_date AS started_at,
                    ROUND(distance::numeric, 1) AS distance_km, duration_min,
                    ROUND(rated_used, 1)        AS rated_range_used_km
               FROM d
              ORDER BY distance DESC NULLS LAST, id
              LIMIT 1)
            UNION ALL
            (SELECT 'duration', id, start_date,
                    ROUND(distance::numeric, 1), duration_min, ROUND(rated_used, 1)
               FROM d
              ORDER BY duration_min DESC NULLS LAST, id
              LIMIT 1)
            UNION ALL
            (SELECT 'efficiency', id, start_date,
                    ROUND(distance::numeric, 1), duration_min, ROUND(rated_used, 1)
               FROM d
              WHERE distance >= 20
                AND rated_used > 0
              ORDER BY distance / rated_used DESC NULLS LAST, id
              LIMIT 1)
        """

        /**
         * 5분 슬롯마다 첫 행 하나만 남긴다.
         *
         * 초안 스펙은 「48시간이면 수백 개라 솎지 않는다」였는데 실측(2026-08-20)이 12,517행이었다
         * (약 750KB). 상한인 168시간에서는 102,141행이라 6MB가 된다. 솎으면 48시간 90개·168시간
         * 440개 안팎이다 — 범위가 미끄러지는 롤링 값이라 재실행마다 달라진다.
         *
         * 잃는 것이 거의 없다. TeslaMate는 차가 깨어 있을 때만 위치를 쌓아 12,517행이 48시간 중
         * 주행·충전한 몇 시간에 몰려 있고 주차 중에는 애초에 행이 없다. 즉 솎아서 잃는 것은 주행
         * 중의 초 단위 해상도뿐이고, 48시간을 한 화면에 그리는 차트에서 그 해상도는 픽셀로도 안
         * 보인다. (`/tesla/charges/{id}/curve`가 1,700개를 안 줄이는 것과 다른 판단인 이유는
         * 자릿수다 — 거기는 한 세션의 곡선이 주인공이다.)
         *
         * **`date`는 슬롯 경계가 아니라 실제 표본 시각이다** — 5분 눈금으로 옮기면 없는 시각의
         * 값이 된다.
         *
         * `battery_level IS NOT NULL`을 거는 이유: 이 배열의 주인공이라 없으면 점을 찍을 수
         * 없다. `usable_battery_level`은 반대로 걸지 않는다 — 실측 3.0%만 채워져 있어 걸면
         * 표본이 통째로 사라진다.
         *
         * 실측 48시간 67ms.
         */
        private const val BATTERY_SAMPLES_SQL = """
            WITH s AS (
                SELECT p.date, p.battery_level, p.usable_battery_level,
                       FLOOR(EXTRACT(epoch FROM p.date) / 300) AS slot
                  FROM positions p
                 WHERE p.date >= :start
                   AND p.date <  :end
                   AND p.battery_level IS NOT NULL
            )
            SELECT DISTINCT ON (s.slot) s.date, s.battery_level, s.usable_battery_level
              FROM s
             ORDER BY s.slot, s.date
        """

        /**
         * 최근 팬텀 드레인. `PARK_DRAIN_MONTHLY_SQL`과 **같은 정의**를 쓴다 —
         * `LEAD`를 전체 주행(유령 포함) 위에서 돌리고, 짝의 어느 쪽이든 마감되지 않았으면
         * 바깥 `WHERE`에서 빼며, 겹침 판정에 `c.end_date IS NOT NULL`을 건다. 급소 근거는
         * `PARK_DRAIN_MONTHLY_SQL`의 KDoc 참조 — 최근 7일은 유령이 없어 값이 안 바뀐다.
         * 두 응답의 같은 값이 달라지면 안 된다.
         *
         * 집계라 행은 늘 온다. 표본이 없으면 `samples`가 0이고 `SUM`이 null이라
         * 매핑에서 0으로 바꾼다 — 「0km 샜다」가 아니라 「표본이 없다」를 `samples`가 말한다.
         *
         * 실측(2026-08-20) 최근 7일 19구간·32.8km·139.4시간.
         */
        private const val PARK_DRAIN_SINCE_SQL = """
            WITH park AS (
                SELECT d.end_date                                                 AS from_date,
                       LEAD(d.start_date)           OVER w                        AS to_date,
                       LEAD(d.end_date)             OVER w                        AS to_end_date,
                       d.end_rated_range_km - LEAD(d.start_rated_range_km) OVER w AS drop_km
                  FROM drives d
                WINDOW w AS (ORDER BY d.start_date)
            )
            SELECT COUNT(*)                 AS samples,
                   ROUND(SUM(p.drop_km), 1) AS rated_km,
                   ROUND(SUM(EXTRACT(epoch FROM (p.to_date - p.from_date)) / 3600.0)::numeric, 1) AS hours
              FROM park p
             WHERE p.from_date    IS NOT NULL
               AND p.to_date      IS NOT NULL
               AND p.to_end_date  IS NOT NULL
               AND p.from_date >= :since
               AND NOT EXISTS (SELECT 1
                                 FROM charging_processes c
                                WHERE c.end_date IS NOT NULL
                                  AND c.start_date < p.to_date
                                  AND c.end_date   > p.from_date)
        """
    }
}
