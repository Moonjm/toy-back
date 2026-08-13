package com.toy.backend.tesla

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.LocalDateTime

/**
 * **nullable 정수·불리언은 `getObject`로 읽는다.** `rs.getInt`는 SQL NULL에 0을 돌려주고
 * `rs.getBoolean`은 false를 돌려줘서, 없는 값과 진짜 0/false가 구분되지 않는다.
 */
@Repository
class JdbcTeslaChargeRepository(
    @Qualifier("teslaMateJdbcClient") private val teslaMateJdbcClient: JdbcClient,
) : TeslaChargeRepository {
    override fun findList(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<ChargeRow> =
        teslaMateJdbcClient
            .sql(LIST_SQL)
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
                    startBatteryLevel = rs.nullableInt("start_battery_level"),
                    endBatteryLevel = rs.nullableInt("end_battery_level"),
                    cost = rs.getBigDecimal("cost"),
                )
            }.list()

    override fun summarize(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): ChargeSummaryRow =
        teslaMateJdbcClient
            .sql(SUMMARY_SQL)
            .param("start", startUtc)
            .param("end", endUtcExclusive)
            .query { rs, _ ->
                ChargeSummaryRow(
                    count = rs.getInt("row_count"),
                    totalEnergyAddedKwh = rs.getBigDecimal("total_energy_added_kwh"),
                    totalCost = rs.getBigDecimal("total_cost"),
                )
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

    private fun ResultSet.nullableInt(column: String): Int? = getObject(column) as Int?

    companion object {
        private const val LIST_SQL = """
            SELECT cp.id,
                   cp.start_date,
                   cp.end_date,
                   cp.duration_min,
                   COALESCE(g.name, a.name, a.display_name) AS location_name,
                   cp.charge_energy_added,
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

        /** 목록을 순회해 더하지 않는다 — 페이지네이션이 붙는 순간 조용히 틀린 합계가 된다. */
        private const val SUMMARY_SQL = """
            SELECT COUNT(*)                    AS row_count,
                   SUM(cp.charge_energy_added) AS total_energy_added_kwh,
                   SUM(cp.cost)                AS total_cost
              FROM charging_processes cp
             WHERE cp.end_date IS NOT NULL
               AND cp.start_date >= :start
               AND cp.start_date <  :end
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
    }
}
