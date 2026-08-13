package com.toy.backend.tesla

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * **nullable 정수·불리언은 `getObject`로 읽는다.** `rs.getInt`는 SQL NULL에 0을,
 * `rs.getBoolean`은 false를 돌려줘서 없는 값과 진짜 0/false가 구분되지 않는다.
 */
@Repository
class JdbcTeslaVehicleRepository(
    @Qualifier("teslaMateJdbcClient") private val teslaMateJdbcClient: JdbcClient,
) : TeslaVehicleRepository {
    override fun driveMonthly(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<DriveMonthRow> =
        teslaMateJdbcClient
            .sql(DRIVE_MONTHLY_SQL)
            .param("start", startUtc)
            .param("end", endUtcExclusive)
            .query { rs, _ ->
                DriveMonthRow(
                    month = YearMonth.from(rs.getObject("month_start", LocalDate::class.java)),
                    count = rs.getInt("row_count"),
                    distanceKm = rs.getBigDecimal("distance_km"),
                    drivingMin = rs.nullableInt("driving_min"),
                )
            }.list()

    /**
     * 7일 창을 먼저 돌린다 — 실측 123ms다. 창 없는 `ORDER BY date DESC`는 3,000만 행에
     * Parallel Seq Scan이 걸려 **실측 11.7초**다.
     *
     * 창이 비면 PK 역순으로 폴백한다. `positions.id`는 serial이고 PK B-tree가 있어 즉시이며,
     * TeslaMate가 시간 순으로 append만 하므로 최대 id가 최신 행이다.
     */
    override fun findLatestPosition(): PositionRow? =
        teslaMateJdbcClient
            .sql(LATEST_POSITION_WINDOW_SQL)
            .query { rs, _ -> rs.toPositionRow() }
            .optional()
            .orElseGet {
                teslaMateJdbcClient
                    .sql(LATEST_POSITION_BY_ID_SQL)
                    .query { rs, _ -> rs.toPositionRow() }
                    .optional()
                    .orElse(null)
            }

    override fun findOpenState(): StateRow? =
        teslaMateJdbcClient
            .sql(OPEN_STATE_SQL)
            .query { rs, _ ->
                StateRow(
                    state = rs.getString("state"),
                    startDateUtc = rs.getObject("start_date", LocalDateTime::class.java),
                )
            }.optional()
            .orElse(null)

    override fun findActivity(): ActivityRow =
        teslaMateJdbcClient
            .sql(ACTIVITY_SQL)
            .query { rs, _ ->
                ActivityRow(
                    charging = rs.getBoolean("charging"),
                    driving = rs.getBoolean("driving"),
                )
            }.single()

    override fun findGeofences(): List<GeofenceRow> =
        teslaMateJdbcClient
            .sql(GEOFENCES_SQL)
            .query { rs, _ ->
                GeofenceRow(
                    name = rs.getString("name"),
                    latitude = rs.getBigDecimal("latitude"),
                    longitude = rs.getBigDecimal("longitude"),
                    radiusM = rs.getInt("radius"),
                )
            }.list()

    private fun ResultSet.toPositionRow() =
        PositionRow(
            dateUtc = getObject("date", LocalDateTime::class.java),
            latitude = getBigDecimal("latitude"),
            longitude = getBigDecimal("longitude"),
            batteryLevel = nullableInt("battery_level"),
            usableBatteryLevel = nullableInt("usable_battery_level"),
            ratedRangeKm = getBigDecimal("rated_battery_range_km"),
            estRangeKm = getBigDecimal("est_battery_range_km"),
            odometerKm = getObject("odometer") as Double?,
            insideTempC = getBigDecimal("inside_temp"),
            outsideTempC = getBigDecimal("outside_temp"),
            climateOn = getObject("is_climate_on") as Boolean?,
            tpmsFl = getBigDecimal("tpms_pressure_fl"),
            tpmsFr = getBigDecimal("tpms_pressure_fr"),
            tpmsRl = getBigDecimal("tpms_pressure_rl"),
            tpmsRr = getBigDecimal("tpms_pressure_rr"),
        )

    private fun ResultSet.nullableInt(column: String): Int? = getObject(column) as Int?

    companion object {
        /**
         * **월 경계를 KST로 자른다.** `date_trunc('month', d.start_date)`를 그냥 쓰면 UTC 기준으로
         * 잘려 KST 8월 1일 새벽 주행이 7월로 들어간다.
         *
         * `distance`는 `double precision`이라 numeric으로 올려 반올림한다 — 부동소수 잡음이
         * 응답에 그대로 나가지 않게 한다.
         */
        private const val DRIVE_MONTHLY_SQL = """
            SELECT date_trunc(
                       'month',
                       d.start_date AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Seoul'
                   )::date                            AS month_start,
                   COUNT(*)                           AS row_count,
                   ROUND(SUM(d.distance)::numeric, 1) AS distance_km,
                   SUM(d.duration_min)                AS driving_min
              FROM drives d
             WHERE d.end_date IS NOT NULL
               AND d.start_date >= :start
               AND d.start_date <  :end
             GROUP BY month_start
             ORDER BY month_start
        """

        private const val POSITION_COLUMNS = """
            p.date, p.latitude, p.longitude,
            p.battery_level, p.usable_battery_level,
            p.rated_battery_range_km, p.est_battery_range_km, p.odometer,
            p.inside_temp, p.outside_temp, p.is_climate_on,
            p.tpms_pressure_fl, p.tpms_pressure_fr, p.tpms_pressure_rl, p.tpms_pressure_rr
        """

        private const val LATEST_POSITION_WINDOW_SQL = """
            SELECT $POSITION_COLUMNS
              FROM positions p
             WHERE p.date >= now() - interval '7 days'
             ORDER BY p.date DESC
             LIMIT 1
        """

        private const val LATEST_POSITION_BY_ID_SQL = """
            SELECT $POSITION_COLUMNS
              FROM positions p
             ORDER BY p.id DESC
             LIMIT 1
        """

        private const val OPEN_STATE_SQL = """
            SELECT s.state, s.start_date
              FROM states s
             WHERE s.end_date IS NULL
             ORDER BY s.start_date DESC
             LIMIT 1
        """

        /**
         * TeslaMate는 `driving`·`charging`을 `states`에 저장하지 않는다
         * (`CREATE TYPE states_status AS ENUM ('online', 'offline', 'asleep')`).
         * 열린 행의 존재로 파생시킨다.
         */
        private const val ACTIVITY_SQL = """
            SELECT EXISTS (SELECT 1 FROM charging_processes WHERE end_date IS NULL) AS charging,
                   EXISTS (SELECT 1 FROM drives             WHERE end_date IS NULL) AS driving
        """

        private const val GEOFENCES_SQL = """
            SELECT g.name, g.latitude, g.longitude, g.radius
              FROM geofences g
        """
    }
}
