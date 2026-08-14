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
 * **nullable 정수·불리언은 `getObject`로 읽는다.** `rs.getInt`는 SQL NULL에 0을 돌려주고
 * `rs.getBoolean`은 false를 돌려줘서, 없는 값과 진짜 0/false가 구분되지 않는다.
 */
@Repository
class JdbcTeslaChargeRepository(
    @Qualifier("teslaMateJdbcClient") private val teslaMateJdbcClient: JdbcClient,
) : TeslaChargeRepository {
    override fun findMissingCost(limit: Int): List<ChargeRow> =
        teslaMateJdbcClient
            .sql(MISSING_COST_SQL)
            .param("limit", limit)
            .query { rs, _ ->
                ChargeRow(
                    id = rs.getLong("id"),
                    startDateUtc = rs.getObject("start_date", LocalDateTime::class.java),
                    endDateUtc = rs.getObject("end_date", LocalDateTime::class.java),
                    durationMin = rs.nullableInt("duration_min"),
                    locationName = rs.getString("location_name"),
                    energyAddedKwh = rs.getBigDecimal("charge_energy_added"),
                    energyUsedKwh = rs.getBigDecimal("charge_energy_used"),
                    startBatteryLevel = rs.nullableInt("start_battery_level"),
                    endBatteryLevel = rs.nullableInt("end_battery_level"),
                    cost = rs.getBigDecimal("cost"),
                )
            }.list()

    override fun countMissingCost(): Int =
        teslaMateJdbcClient
            .sql(COUNT_MISSING_COST_SQL)
            .query { rs, _ ->
                rs.getInt("row_count")
            }.single()

    override fun findDetail(id: Long): ChargeDetailRow? =
        teslaMateJdbcClient
            .sql(DETAIL_SQL)
            .param("id", id)
            .query { rs, _ ->
                ChargeDetailRow(
                    id = rs.getLong("id"),
                    startDateUtc = rs.getObject("start_date", LocalDateTime::class.java),
                    endDateUtc = rs.getObject("end_date", LocalDateTime::class.java),
                    durationMin = rs.nullableInt("duration_min"),
                    energyAddedKwh = rs.getBigDecimal("charge_energy_added"),
                    energyUsedKwh = rs.getBigDecimal("charge_energy_used"),
                    startBatteryLevel = rs.nullableInt("start_battery_level"),
                    endBatteryLevel = rs.nullableInt("end_battery_level"),
                    startRatedRangeKm = rs.getBigDecimal("start_rated_range_km"),
                    endRatedRangeKm = rs.getBigDecimal("end_rated_range_km"),
                    outsideTempAvg = rs.getBigDecimal("outside_temp_avg"),
                    geofenceName = rs.getString("geofence_name"),
                    address = rs.getString("address"),
                    cost = rs.getBigDecimal("cost"),
                )
            }.optional()
            .orElse(null)

    override fun findChargeStats(id: Long): ChargeStatsRow =
        teslaMateJdbcClient
            .sql(STATS_SQL)
            .param("id", id)
            .query { rs, _ ->
                ChargeStatsRow(
                    maxPowerKw = rs.nullableInt("max_power_kw"),
                    avgPowerKw = rs.getBigDecimal("avg_power_kw"),
                    fastCharger = rs.getObject("fast_charger") as Boolean?,
                    fastChargerBrand = rs.getString("fast_charger_brand"),
                    fastChargerType = rs.getString("fast_charger_type"),
                )
            }.single()

    override fun updateCost(
        id: Long,
        cost: BigDecimal,
    ): Int =
        teslaMateJdbcClient
            .sql(UPDATE_COST_SQL)
            .param("id", id)
            .param("cost", cost)
            .update()

    override fun chargeMonthly(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<ChargeMonthRow> =
        teslaMateJdbcClient
            .sql(CHARGE_MONTHLY_SQL)
            .param("start", startUtc)
            .param("end", endUtcExclusive)
            .query { rs, _ ->
                ChargeMonthRow(
                    month = YearMonth.from(rs.getObject("month_start", LocalDate::class.java)),
                    count = rs.getInt("row_count"),
                    energyAddedKwh = rs.getBigDecimal("energy_added_kwh"),
                    energyUsedKwh = rs.getBigDecimal("energy_used_kwh"),
                    cost = rs.getBigDecimal("cost"),
                )
            }.list()

    override fun findMonthCharges(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<ChargeRow> =
        teslaMateJdbcClient
            .sql(MONTH_CHARGES_SQL)
            .param("start", startUtc)
            .param("end", endUtcExclusive)
            .query { rs, _ ->
                ChargeRow(
                    id = rs.getLong("id"),
                    startDateUtc = rs.getObject("start_date", LocalDateTime::class.java),
                    endDateUtc = rs.getObject("end_date", LocalDateTime::class.java),
                    durationMin = rs.nullableInt("duration_min"),
                    locationName = rs.getString("location_name"),
                    energyAddedKwh = rs.getBigDecimal("charge_energy_added"),
                    energyUsedKwh = rs.getBigDecimal("charge_energy_used"),
                    startBatteryLevel = rs.nullableInt("start_battery_level"),
                    endBatteryLevel = rs.nullableInt("end_battery_level"),
                    cost = rs.getBigDecimal("cost"),
                )
            }.list()

    private fun ResultSet.nullableInt(column: String): Int? = getObject(column) as Int?

    companion object {
        /**
         * **최근 한 달 안의 미등록 건만 낸다.** 오래된 것까지 다 내리면 채워 넣을 마음이 안 드는
         * 길이가 되고, 실제로 채우는 것은 최근 것이다.
         *
         * 경계를 SQL에서 잡는 이유는 Kotlin에서 `now()`를 쓰면 시계 주입과 테스트 이음새가
         * 따라붙기 때문이다. `start_date`는 타임존 없는 컬럼에 든 UTC 값이라
         * `now() AT TIME ZONE 'UTC'`로 맞춰야 창이 어긋나지 않는다 —
         * `now()`(timestamptz)와 그냥 비교하면 세션 타임존만큼(KST면 9시간) 짧아진다.
         */
        private const val MISSING_COST_SQL = """
            SELECT cp.id,
                   cp.start_date,
                   cp.end_date,
                   cp.duration_min,
                   COALESCE(g.name, a.name, a.display_name) AS location_name,
                   cp.charge_energy_added,
                   cp.charge_energy_used,
                   cp.start_battery_level,
                   cp.end_battery_level,
                   cp.cost
              FROM charging_processes cp
              LEFT JOIN geofences g ON g.id = cp.geofence_id
              LEFT JOIN addresses a ON a.id = cp.address_id
             WHERE cp.end_date IS NOT NULL
               AND cp.cost IS NULL
               AND cp.start_date >= (now() AT TIME ZONE 'UTC') - interval '1 month'
             ORDER BY cp.start_date DESC
             LIMIT :limit
        """

        /**
         * **목록과 같은 창을 쓴다.** 목록만 좁히면 배지가 「37건」인데 목록에는 3건만 보이고,
         * 채워도 배지가 거의 줄지 않아 무엇이 남았는지 읽히지 않는다.
         */
        private const val COUNT_MISSING_COST_SQL = """
            SELECT COUNT(*) AS row_count
              FROM charging_processes cp
             WHERE cp.end_date IS NOT NULL
               AND cp.cost IS NULL
               AND cp.start_date >= (now() AT TIME ZONE 'UTC') - interval '1 month'
        """

        private const val DETAIL_SQL = """
            SELECT cp.id,
                   cp.start_date,
                   cp.end_date,
                   cp.duration_min,
                   cp.charge_energy_added,
                   cp.charge_energy_used,
                   cp.start_battery_level,
                   cp.end_battery_level,
                   cp.start_rated_range_km,
                   cp.end_rated_range_km,
                   cp.outside_temp_avg,
                   g.name         AS geofence_name,
                   a.display_name AS address,
                   cp.cost
              FROM charging_processes cp
              LEFT JOIN geofences g ON g.id = cp.geofence_id
              LEFT JOIN addresses a ON a.id = cp.address_id
             WHERE cp.id = :id
               AND cp.end_date IS NOT NULL
        """

        /**
         * 샘플이 하나도 없어도 집계 쿼리는 행 하나를 돌려준다 — 모든 컬럼이 NULL이다.
         * 브랜드·타입은 급속일 때만 뽑는다. 완속 샘플에도 값이 들어 있을 수 있다.
         */
        private const val STATS_SQL = """
            SELECT MAX(c.charger_power)                                            AS max_power_kw,
                   ROUND(AVG(c.charger_power) FILTER (WHERE c.charger_power > 0), 1) AS avg_power_kw,
                   BOOL_OR(c.fast_charger_present)                                 AS fast_charger,
                   MAX(c.fast_charger_brand) FILTER (WHERE c.fast_charger_present) AS fast_charger_brand,
                   MAX(c.fast_charger_type)  FILTER (WHERE c.fast_charger_present) AS fast_charger_type
              FROM charges c
             WHERE c.charging_process_id = :id
        """

        /**
         * **TeslaMate DB에 쓰는 유일한 문장이다.** 진행 중인 충전을 막는 이유는
         * TeslaMate가 세션을 마감하며 지오펜스 요금으로 cost를 덮어쓰기 때문이다.
         */
        private const val UPDATE_COST_SQL = """
            UPDATE charging_processes
               SET cost = :cost
             WHERE id = :id
               AND end_date IS NOT NULL
        """

        /**
         * **월 경계를 KST로 자른다.** `start_date`는 타임존 없는 컬럼에 든 UTC 값이라,
         * `AT TIME ZONE 'UTC'`로 timestamptz를 만든 뒤 `AT TIME ZONE 'Asia/Seoul'`로 KST 벽시계로
         * 옮기고 자른다. 그냥 `date_trunc('month', cp.start_date)`를 쓰면 UTC 기준으로 잘려
         * **KST 8월 1일 0시 30분 충전이 7월로 들어간다.**
         */
        private const val CHARGE_MONTHLY_SQL = """
            SELECT date_trunc(
                       'month',
                       cp.start_date AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Seoul'
                   )::date                     AS month_start,
                   COUNT(*)                                       AS row_count,
                   ROUND(SUM(cp.charge_energy_added)::numeric, 1) AS energy_added_kwh,
                   ROUND(SUM(cp.charge_energy_used)::numeric, 1)  AS energy_used_kwh,
                   SUM(cp.cost)                                   AS cost
              FROM charging_processes cp
             WHERE cp.end_date IS NOT NULL
               AND cp.start_date >= :start
               AND cp.start_date <  :end
             GROUP BY month_start
             ORDER BY month_start
        """

        private const val MONTH_CHARGES_SQL = """
            SELECT cp.id,
                   cp.start_date,
                   cp.end_date,
                   cp.duration_min,
                   COALESCE(g.name, a.name, a.display_name) AS location_name,
                   cp.charge_energy_added,
                   cp.charge_energy_used,
                   cp.start_battery_level,
                   cp.end_battery_level,
                   cp.cost
              FROM charging_processes cp
              LEFT JOIN geofences g ON g.id = cp.geofence_id
              LEFT JOIN addresses a ON a.id = cp.address_id
             WHERE cp.end_date IS NOT NULL
               AND cp.start_date >= :start
               AND cp.start_date <  :end
             ORDER BY cp.start_date DESC
        """
    }
}
