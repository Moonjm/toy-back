package com.toy.backend.tesla

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
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
                    ratedKm = rs.getBigDecimal("park_drain_rated_km"),
                    samples = rs.getInt("park_drain_samples"),
                )
            }.list()

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
         */
        private const val PARK_DRAIN_MONTHLY_SQL = """
            WITH park AS (
                SELECT d.end_date                                                       AS from_date,
                       LEAD(d.start_date)           OVER w                              AS to_date,
                       d.end_rated_range_km - LEAD(d.start_rated_range_km) OVER w       AS drop_km
                  FROM drives d
                 WHERE d.end_date IS NOT NULL
                WINDOW w AS (ORDER BY d.start_date)
            )
            SELECT date_trunc(
                       'month',
                       p.from_date AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Seoul'
                   )::date               AS month_start,
                   COUNT(*)              AS park_drain_samples,
                   ROUND(SUM(p.drop_km), 1) AS park_drain_rated_km
              FROM park p
             WHERE p.to_date IS NOT NULL
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
    }
}
