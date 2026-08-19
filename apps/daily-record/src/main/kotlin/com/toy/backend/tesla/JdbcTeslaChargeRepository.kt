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

    override fun findTotals(): ChargeTotalsRow =
        teslaMateJdbcClient
            .sql(TOTALS_SQL)
            .query { rs, _ ->
                ChargeTotalsRow(
                    chargeCount = rs.getInt("charge_count"),
                    energyAddedKwh = rs.getBigDecimal("energy_added_kwh"),
                    energyUsedKwh = rs.getBigDecimal("energy_used_kwh"),
                    cost = rs.getBigDecimal("cost"),
                    costMissingCount = rs.getInt("cost_missing_count"),
                    costMissingEnergyUsedKwh = rs.getBigDecimal("cost_missing_energy_used_kwh"),
                    firstChargedUtc = rs.getObject("first_charged_at", LocalDateTime::class.java),
                    fastChargeCount = rs.getInt("fast_charge_count"),
                    fastEnergyAddedKwh = rs.getBigDecimal("fast_energy_added_kwh"),
                    fastEnergyUsedKwh = rs.getBigDecimal("fast_energy_used_kwh"),
                    fastCost = rs.getBigDecimal("fast_cost"),
                    fastCostMissingCount = rs.getInt("fast_cost_missing_count"),
                    fastCostMissingEnergyUsedKwh = rs.getBigDecimal("fast_cost_missing_energy_used_kwh"),
                )
            }.single()

    override fun findCurve(id: Long): List<ChargeCurveSampleRow> =
        teslaMateJdbcClient
            .sql(CURVE_SQL)
            .param("id", id)
            .query { rs, _ ->
                ChargeCurveSampleRow(
                    dateUtc = rs.getObject("date", LocalDateTime::class.java),
                    powerKw = rs.nullableInt("charger_power"),
                    batteryLevel = rs.nullableInt("battery_level"),
                )
            }.list()

    override fun existsCompleted(id: Long): Boolean =
        teslaMateJdbcClient
            .sql(EXISTS_COMPLETED_SQL)
            .param("id", id)
            .query { rs, _ -> rs.getBoolean("found") }
            .single()

    private fun ResultSet.nullableInt(column: String): Int? = getObject(column) as Int?

    companion object {
        /**
         * **최근 한 달 안의 미등록 건만 낸다.** 오래된 것까지 다 내리면 채워 넣을 마음이 안 드는
         * 길이가 되고, 실제로 채우는 것은 최근 것이다.
         *
         * 경계를 SQL에서 잡는 이유는 Kotlin에서 `now()`를 쓰면 시계 주입과 테스트 이음새가
         * 따라붙기 때문이다. `start_date`는 타임존 없는 컬럼에 든 UTC 값이라
         * `now() AT TIME ZONE 'UTC'`로 맞춰야 범위가 어긋나지 않는다 —
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
         * **목록과 같은 범위를 쓴다.** 목록만 좁히면 배지가 「37건」인데 목록에는 3건만 보이고,
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

        /**
         * 두 SQL이 공유하는 **모집단 정의**다. 지표별 조건이 아니다 —
         * 「무엇을 한 건의 충전으로 셀 것인가」이고, 두 응답의 건수가 서로 말이 되려면 같아야 한다.
         *
         * `charge_energy_added > 0 OR cost IS NOT NULL`인 이유: 케이블만 꽂았다 뺀 축퇴 세션은
         * SoC가 그대로이고 kWh가 0이라 누적에 낄 이유가 없다(실측 2026-08-18로 11건).
         * 다만 그중 `id=15`는 TeslaMate가 데이터를 통째로 잃었는데 **10,360원은 실제로 낸 돈**이라
         * 남긴다 — 빼면 누적 비용이 실제 지출과 어긋난다.
         *
         * **이 조건은 문법상 `charge_energy_added`(지표 A)의 컬럼으로 쓰여 있어서, 원리상 다른
         * 지표(`charge_energy_used`, 지표 B)의 표본을 깎을 수 있는 모양이다.** 실측으로 그렇지
         * 않다는 것을 확인했다(2026-08-18) — 이 조건으로 제외되는 10건 중 `charge_energy_used > 0`인
         * 것이 0건이고, 그로 인한 손실이 0.00 kWh다. 즉 이 조건은 문법상 `charge_energy_added`의
         * 조건이지만 실제로 `charge_energy_used` 표본을 깎지 않는다. 이걸 적어 두는 이유는 1단계에서
         * 「지표 A의 조건이 지표 B의 표본을 깎는다」로 같은 계열 지적을 세 번 받았고, 이 자리가 그
         * 지적이 다시 발화할 수 있는 모양이라 재 봤기 때문이다.
         */
        private const val CHARGE_POPULATION = """
                   cp.end_date IS NOT NULL
               AND (cp.charge_energy_added > 0 OR cp.cost IS NOT NULL)
        """

        /**
         * 세션 평균 전력(kW). **`charges`를 조인하지 않고 급속을 파생하는 식이다.**
         *
         * `charges`가 485,830행이라 세션마다 LATERAL로 `bool_or(fast_charger_present)`를 보면
         * **실측 877ms**이고, 이 파생은 **9.6ms**다. 그러면서 474건 중 `fast_charger_present`와
         * 어긋난 건이 **0건**이었다.
         *
         * 임계값 15kW가 안전한 이유: 실측 분포에 골이 있다 — 완속 432건이 전부 0~9.3kW이고
         * 급속은 20.8kW부터다. 15는 그 한가운데다.
         *
         * `COALESCE(..., 0)`이 **평균을 못 내는 세션을 완속으로 보낸다.** `duration_min`이 null인
         * `id=15` 하나뿐이고, 그래야 `급속 + 완속 = 합계` 불변식이 선다.
         */
        private const val FAST_CHARGE = """
            COALESCE(cp.charge_energy_added / NULLIF(cp.duration_min, 0) * 60, 0) >= 15
        """

        /**
         * 전 기간 누적. **한 번 훑어 합계와 급속분을 함께 낸다** — 완속은 서비스가 뺄셈으로 만든다.
         *
         * **급속/미입력을 WHERE로 깎지 않고 `FILTER`로 가른다.** 1단계에서 공통 WHERE에 두 지표의
         * 조건을 섞어 같은 계열 지적을 세 번 받았다. `FILTER`는 모집단을 줄이지 않으므로 어느
         * 지표가 다른 지표의 표본을 깎을 길이 구조적으로 없다.
         *
         * `cost`에 `SUM`을 그냥 거는 것이 맞다 — null을 건너뛰므로 **실제로 낸 돈**이 된다.
         * 미입력분의 크기는 `cost_missing_*`이 따로 낸다.
         *
         * `charge_energy_added`·`charge_energy_used`·`cost`는 `numeric`이라 그대로 `ROUND` 한다.
         */
        private const val TOTALS_SQL = """
            SELECT COUNT(*)                                                                AS charge_count,
                   ROUND(SUM(cp.charge_energy_added), 1)                                   AS energy_added_kwh,
                   ROUND(SUM(cp.charge_energy_used), 1)                                    AS energy_used_kwh,
                   ROUND(SUM(cp.cost), 0)                                                  AS cost,
                   COUNT(*) FILTER (WHERE cp.cost IS NULL)                                 AS cost_missing_count,
                   ROUND(SUM(cp.charge_energy_used) FILTER (WHERE cp.cost IS NULL), 1)     AS cost_missing_energy_used_kwh,
                   MIN(cp.start_date)                                                      AS first_charged_at,
                   COUNT(*) FILTER (WHERE $FAST_CHARGE)                                    AS fast_charge_count,
                   ROUND(SUM(cp.charge_energy_added) FILTER (WHERE $FAST_CHARGE), 1)       AS fast_energy_added_kwh,
                   ROUND(SUM(cp.charge_energy_used)  FILTER (WHERE $FAST_CHARGE), 1)       AS fast_energy_used_kwh,
                   ROUND(SUM(cp.cost)                FILTER (WHERE $FAST_CHARGE), 0)       AS fast_cost,
                   COUNT(*) FILTER (WHERE $FAST_CHARGE AND cp.cost IS NULL)                AS fast_cost_missing_count,
                   ROUND(SUM(cp.charge_energy_used)
                         FILTER (WHERE $FAST_CHARGE AND cp.cost IS NULL), 1)               AS fast_cost_missing_energy_used_kwh
              FROM charging_processes cp
             WHERE $CHARGE_POPULATION
        """

        /**
         * 세션 하나의 kW 샘플. `charges_charging_process_id_index`(B-tree)가 있어 즉시다.
         *
         * **줄이지 않는다.** 급속은 250~360개, 완속은 700~1,700개다(실측). 사용자 2명·하루 수십 건
         * 규모에서 1,700행 JSON은 수십 KB이고, 서버가 줄이면 「어느 점을 버릴지」를 서버가 정하게
         * 된다 — 이 저장소가 나눗셈을 앱에 맡겨 온 것과 같은 계열이다.
         */
        private const val CURVE_SQL = """
            SELECT c.date,
                   c.charger_power,
                   c.battery_level
              FROM charges c
             WHERE c.charging_process_id = :id
             ORDER BY c.date
        """

        /**
         * 곡선의 404 판정. **`findCurve`가 빈 리스트를 주는 이유가 둘이라** 따로 본다 —
         * 「없는 id·진행 중」과 「샘플이 없는 세션」이다. 앞은 404, 뒤는 빈 배열이어야 한다.
         *
         * 모집단 조건을 걸지 않는다 — 축퇴 세션이라도 그 id의 곡선을 물으면 「없다」가 아니라
         * 「샘플이 없다」가 맞다.
         */
        private const val EXISTS_COMPLETED_SQL = """
            SELECT EXISTS (SELECT 1
                             FROM charging_processes cp
                            WHERE cp.id = :id
                              AND cp.end_date IS NOT NULL) AS found
        """
    }
}
