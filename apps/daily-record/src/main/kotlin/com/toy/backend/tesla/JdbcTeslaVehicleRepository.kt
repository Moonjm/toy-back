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

    override fun batteryHealthMonthly(): List<BatteryHealthMonthRow> =
        teslaMateJdbcClient
            .sql(BATTERY_HEALTH_MONTHLY_SQL)
            .query { rs, _ ->
                BatteryHealthMonthRow(
                    month = YearMonth.from(rs.getObject("month_start", LocalDate::class.java)),
                    fullRangeKm = rs.getBigDecimal("full_range_km"),
                    capacityKwh = rs.getBigDecimal("capacity_kwh"),
                    sampleCount = rs.getInt("row_count"),
                    capacitySampleCount = rs.getInt("capacity_row_count"),
                )
            }.list()

    override fun driveTemperatureBuckets(months: Int): List<DriveTemperatureBucketRow> =
        teslaMateJdbcClient
            .sql(DRIVE_TEMPERATURE_BUCKETS_SQL)
            .param("months", months)
            .query { rs, _ ->
                DriveTemperatureBucketRow(
                    bucket = rs.getInt("bucket"),
                    driveCount = rs.getInt("drive_count"),
                    distanceKm = rs.getBigDecimal("distance_km"),
                    ratedRangeUsedKm = rs.getBigDecimal("rated_range_used_km"),
                )
            }.list()

    override fun driveTimes(months: Int): List<DriveTimeRow> =
        teslaMateJdbcClient
            .sql(DRIVE_TIMES_SQL)
            .param("months", months)
            .query { rs, _ ->
                DriveTimeRow(
                    weekday = rs.getInt("weekday"),
                    hour = rs.getInt("hour"),
                    count = rs.getInt("row_count"),
                )
            }.list()

    override fun driveDistanceBuckets(months: Int): List<DriveDistanceBucketRow> =
        teslaMateJdbcClient
            .sql(DRIVE_DISTANCE_BUCKETS_SQL)
            .param("months", months)
            .query { rs, _ ->
                DriveDistanceBucketRow(
                    bucket = rs.getInt("bucket"),
                    driveCount = rs.getInt("drive_count"),
                    distanceKm = rs.getBigDecimal("distance_km"),
                )
            }.list()

    override fun drivePlaces(months: Int): List<DrivePlaceRow> =
        teslaMateJdbcClient
            .sql(DRIVE_PLACES_SQL)
            .param("months", months)
            .query { rs, _ ->
                DrivePlaceRow(
                    name = rs.getString("name"),
                    driveCount = rs.getInt("drive_count"),
                    distanceKm = rs.getBigDecimal("distance_km"),
                )
            }.list()

    override fun carEfficiency(): BigDecimal? =
        teslaMateJdbcClient
            .sql(CAR_EFFICIENCY_SQL)
            .query { rs, _ -> rs.getBigDecimal("efficiency") }
            .optional()
            .orElse(null)

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
                   SUM(d.duration_min)::int           AS driving_min
              FROM drives d
             WHERE d.end_date IS NOT NULL
               AND d.start_date >= :start
               AND d.start_date <  :end
             GROUP BY month_start
             ORDER BY month_start
        """

        /**
         * **월 경계를 `end_date` 기준 KST로 자른다.** 측정 시점은 충전이 끝난 때다 —
         * `start_date`로 자르면 자정을 넘긴 오버나이트 충전이 앞 달로 들어간다.
         *
         * **평균이 아니라 중앙값이다.** 급속 충전 직후에는 `rated_battery_range_km`가 실제보다
         * 높거나 낮게 잡히는 일이 있고, 표본이 두세 개뿐인 달에서 평균은 그 한 건에 끌려간다.
         *
         * `percentile_cont`는 `double precision`을 받으므로 명시적으로 캐스팅하고, 결과는
         * `numeric`으로 되돌려 소수 한 자리로 반올림한다 — 부동소수 잡음이 응답에 그대로
         * 나가지 않게 한다. **null 입력은 무시하므로** 그 달에 용량 표본이 하나도 없으면
         * `capacity_kwh`가 null이 되고 `COUNT(capacity_kwh)`가 0이 된다.
         *
         * **분모가 0이 될 길을 WHERE에서 막는다.** `end_battery_level >= 80`이라 0이 아니고,
         * 용량 쪽은 ΔSoC ≥ 40이라 역시 0이 아니다. 이 저장소가 나눗셈을 앱에 맡겨 온
         * 이유(0·null 처리를 서버가 정하면 화면이 따라야 한다)가 여기서는 생기지 않는다 —
         * 중앙값은 표본 집합 위의 연산이라 나누기 전에는 낼 수 없어 예외로 둔다.
         *
         * 만충 환산이 신차 기준을 넘는 값(냉간·BMS 재보정)은 나올 수 있다. **자르지 않는다.**
         *
         * **`start_battery_level`을 공통 WHERE에서 거르지 않는다.** 만충 환산은 시작 레벨을
         * 쓰지 않으므로, 거기서 걸러 버리면 용량만의 조건이 만충 환산 표본까지 끌고 내려간다.
         * 용량 쪽 `CASE`가 이미 null-safe다 — start가 null이면 `NULL >= 40`이 NULL이라 `WHEN`이
         * 참이 되지 않고 `capacity_kwh`가 NULL로 떨어져 `COUNT`가 건너뛴다.
         *
         * `charging_processes`는 수백 행이라 전체 스캔이라도 즉시 끝난다 — 창을 두지 않는다.
         */
        private const val BATTERY_HEALTH_MONTHLY_SQL = """
            WITH sample AS (
                SELECT date_trunc(
                           'month',
                           cp.end_date AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Seoul'
                       )::date AS month_start,
                       cp.end_rated_range_km / cp.end_battery_level * 100 AS full_range_km,
                       CASE
                           WHEN cp.end_battery_level - cp.start_battery_level >= 40
                            AND cp.charge_energy_added > 0
                           THEN cp.charge_energy_added
                                / (cp.end_battery_level - cp.start_battery_level) * 100
                       END AS capacity_kwh
                  FROM charging_processes cp
                 WHERE cp.end_date IS NOT NULL
                   AND cp.end_battery_level >= 80
                   AND cp.end_rated_range_km IS NOT NULL
            )
            SELECT month_start,
                   COUNT(*)            AS row_count,
                   COUNT(capacity_kwh) AS capacity_row_count,
                   ROUND(percentile_cont(0.5) WITHIN GROUP (
                       ORDER BY full_range_km::double precision)::numeric, 1) AS full_range_km,
                   ROUND(percentile_cont(0.5) WITHIN GROUP (
                       ORDER BY capacity_kwh::double precision)::numeric, 1)  AS capacity_kwh
              FROM sample
             GROUP BY month_start
             ORDER BY month_start
        """

        /**
         * **기간 창의 기준을 `(now() AT TIME ZONE 'UTC')`로 맞춘다.** `end_date`는 타임존 없는
         * 컬럼에 든 UTC 값이라 `now()`(timestamptz)와 그냥 비교하면 세션 타임존만큼(KST면
         * 9시간) 창이 어긋난다 — `ACTIVITY_SQL`이 같은 이유로 같은 꼴을 쓴다.
         *
         * 네 주행 쿼리가 이 한 줄을 함께 쓴다.
         */
        private const val DRIVE_WINDOW = """
                   AND d.end_date >= (now() AT TIME ZONE 'UTC') - (:months * interval '1 month')
        """

        /**
         * **버킷 경계는 `TeslaVehicleService.TEMPERATURE_BUCKETS`와 같은 숫자여야 한다** —
         * 여기는 임계값으로, 거기는 응답 라벨(`fromC`·`toC`)로 쓴다. 한쪽만 고치면 응답의
         * 라벨과 실제 집계가 어긋난다.
         *
         * **자기 지표가 쓰는 조건만 건다.** `outside_temp_avg IS NOT NULL`은 어느 버킷에도
         * 넣을 수 없어서고, `ΔratedRange > 0`은 넣으면 전비가 무한대가 되기 때문이다.
         * 실측(2026-08-17)으로 후자에 걸리는 주행이 5,055건 중 447건인데 431건이 차이가
         * 정확히 0이고 평균 거리가 0.2km다 — 주행가능거리 표시가 움직이지 않을 만큼 짧은
         * 주행이라 거리 손실은 사실상 없다.
         *
         * **`ELSE 5`는 `outside_temp_avg IS NOT NULL`이 앞에서 걸러 준다는 것에 기대고 있다.**
         * 지금은 WHERE가 온도 미상 주행을 이미 뺐으니 `ELSE`는 「30℃ 이상」만 받는다. 나중에
         * 누군가 「ELSE가 다 받으니 이 필터는 필요 없다」고 판단해 그 줄을 지우면, 온도 미상
         * 주행이 전부 「30℃ 이상」 버킷으로 조용히 들어간다 — 값이 틀리는데 행 수는 늘어
         * 정상처럼 보인다.
         *
         * `distance`는 `double precision`이라 `::numeric`으로 올려 반올림한다. 주행가능거리는
         * 이미 `numeric`이라 그대로 `ROUND`한다.
         */
        private const val DRIVE_TEMPERATURE_BUCKETS_SQL = """
            SELECT CASE WHEN d.outside_temp_avg <  0 THEN 1
                        WHEN d.outside_temp_avg < 10 THEN 2
                        WHEN d.outside_temp_avg < 20 THEN 3
                        WHEN d.outside_temp_avg < 30 THEN 4
                        ELSE 5
                   END                                                          AS bucket,
                   COUNT(*)                                                     AS drive_count,
                   ROUND(SUM(d.distance)::numeric, 1)                           AS distance_km,
                   ROUND(SUM(d.start_rated_range_km - d.end_rated_range_km), 1) AS rated_range_used_km
              FROM drives d
             WHERE d.end_date IS NOT NULL
               AND d.distance > 0
               $DRIVE_WINDOW
               AND d.outside_temp_avg IS NOT NULL
               AND d.start_rated_range_km - d.end_rated_range_km > 0
             GROUP BY bucket
             ORDER BY bucket
        """

        /**
         * 차량이 1대다(`cars` 1행). 두 대가 되면 두 차의 값이 조용히 섞이는데, 그것은
         * `/tesla/summary`·`/tesla/status`의 모든 SQL이 이미 안고 있는 전제와 같다.
         */
        private const val CAR_EFFICIENCY_SQL = """
            SELECT c.efficiency
              FROM cars c
             ORDER BY c.id
             LIMIT 1
        """

        /**
         * **KST로 옮긴 뒤 뽑는다.** UTC로 뽑으면 아침 8시 출근이 밤 11시로 찍힌다 —
         * 실측(2026-08-17)으로 최근 12개월 상위 칸이 월 08시·화 17시·월 17시·화 08시라
         * 출퇴근이 그대로 보인다.
         *
         * `dow`는 **0이 일요일**이다. 앱도 그대로 읽는다.
         *
         * **0인 칸은 행이 오지 않는다.** 168칸 중 대부분이 0이고, 히트맵은 없는 칸을 빈칸으로
         * 그리면 된다 — 온도·거리 버킷이 빈 자리를 지키는 것과 반대다. 그쪽은 축이 흔들리지만
         * 히트맵은 그렇지 않다.
         *
         * **시각은 `start_date`로 뽑지만 기간 창은 `end_date`로 건다** — 창에 드는 기준을 네
         * 쿼리가 같이 쓰게 하려는 것이고, 자정을 걸친 주행 한 건의 차이일 뿐이다.
         *
         * **온도·주행가능거리 조건을 걸지 않는다.** 시간대는 둘 다 쓰지 않는다.
         */
        private const val DRIVE_TIMES_SQL = """
            SELECT EXTRACT(dow  FROM d.start_date AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Seoul')::int AS weekday,
                   EXTRACT(hour FROM d.start_date AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Seoul')::int AS hour,
                   COUNT(*)                                                                          AS row_count
              FROM drives d
             WHERE d.end_date IS NOT NULL
               AND d.distance > 0
               $DRIVE_WINDOW
             GROUP BY weekday, hour
             ORDER BY weekday, hour
        """

        /**
         * **버킷 경계는 `TeslaVehicleService.DISTANCE_BUCKETS`와 같은 숫자여야 한다.**
         *
         * **온도·주행가능거리 조건을 걸지 않는다.** 거리 분포는 둘 다 쓰지 않는다.
         */
        private const val DRIVE_DISTANCE_BUCKETS_SQL = """
            SELECT CASE WHEN d.distance <   5 THEN 1
                        WHEN d.distance <  20 THEN 2
                        WHEN d.distance <  50 THEN 3
                        WHEN d.distance < 100 THEN 4
                        ELSE 5
                   END                                AS bucket,
                   COUNT(*)                           AS drive_count,
                   ROUND(SUM(d.distance)::numeric, 1) AS distance_km
              FROM drives d
             WHERE d.end_date IS NOT NULL
               AND d.distance > 0
               $DRIVE_WINDOW
             GROUP BY bucket
             ORDER BY bucket
        """

        /**
         * 도착지 이름을 낸다. **지오펜스가 없으면 주소로 떨어진다.**
         *
         * 원래는 `end_geofence_id`만 보고 「주소는 내지 않는다」로 두었다. 그 근거로 든 것이
         * `/tesla/status`였는데, **비교 대상을 잘못 골랐다.** 상태의 `locationName`은 지금 이
         * 순간의 좌표를 반경으로 판정하는 값이라 이름이 붙거나 안 붙거나 둘 중 하나이고,
         * 주소를 낼 자리가 애초에 없다. 반면 **이미 지나간 도착지를 세는** 충전 목록·충전 상세는
         * `COALESCE(g.name, a.name, a.display_name)`으로 주소까지 쓴다 — 여기가 같은 부류다.
         * 게다가 이 DB는 `geofences`가 0행이라 옛 방침의 실제 효과는 **카드가 영원히 비는 것**뿐이었다.
         *
         * **COALESCE 순서가 표시 품질을 정한다**(실측 2026-08-19, 최근 12개월 958건):
         * `a.name`이 819건(85.5%)을 짧은 이름으로 덮고, 나머지 139건은 `a.road`가 거의 다
         * 채운다. `a.display_name`으로 바로 떨어뜨리면 「Goyang-daero, 일산2동, Ilsanseo-gu,
         * Goyang-si, 10360, South Korea」 같은 한 줄이 목록에 들어온다 — 그래서 `road`·`city`를
         * 사이에 끼운다. `display_name`은 마지막 수단이다.
         *
         * **id가 아니라 표시 이름으로 묶는다.** 주소는 재지오코딩 결과마다 행이 갈리는데,
         * 사람이 같은 곳으로 읽는 것을 두 줄로 내면 목록이 무너진다. 실측으로 상위 12개 중
         * 갈려 있던 것은 하나뿐이었다(53+2 → 55). 묶고 나면 이름이 결과 안에서 유일해져
         * 앱의 행 식별도 함께 안정된다.
         *
         * **`ORDER BY`의 마지막 열로 이름을 둔다.** 건수·거리가 모두 같은 도착지가
         * `LIMIT 10` 경계에 걸리면 tie-breaker 없이는 어느 것이 잘릴지 실행마다 달라질 수 있다.
         *
         * `addresses`는 1,084행, `geofences`는 수십 행이고 `drives`는 수천 행이라
         * 해시 조인으로 즉시 끝난다.
         */
        private const val DRIVE_PLACES_SQL = """
            SELECT COALESCE(g.name, a.name, a.road, a.city, a.display_name) AS name,
                   COUNT(*)                          AS drive_count,
                   ROUND(SUM(d.distance)::numeric, 1) AS distance_km
              FROM drives d
              LEFT JOIN geofences g ON g.id = d.end_geofence_id
              LEFT JOIN addresses a ON a.id = d.end_address_id
             WHERE d.end_date IS NOT NULL
               AND d.distance > 0
               AND COALESCE(g.name, a.name, a.road, a.city, a.display_name) IS NOT NULL
               $DRIVE_WINDOW
             GROUP BY 1
             ORDER BY drive_count DESC, distance_km DESC, name
             LIMIT 10
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
             WHERE p.date >= (now() AT TIME ZONE 'UTC') - interval '7 days'
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
         *
         * **열린 행에 최근성 조건이 반드시 있어야 한다.** TeslaMate가 충전·주행 중에 죽거나 차가
         * 오프라인이 되면 세션이 마감되지 않고 `end_date IS NULL`인 채로 영원히 남는다. 실제
         * 이 DB에는 그렇게 쌓인 유령이 충전 6건(2021~2025년)·주행 12건(2022~2024년) 있었고,
         * 조건 없는 `EXISTS`는 그중 하나만 있어도 **늘 「지금 충전 중」을 냈다** — `state`가
         * 배포 이후 한 번도 맞은 적이 없었다.
         *
         * 24시간인 이유: 완속 오버나이트 충전이 10시간쯤이고 한 번에 24시간 연속 주행은 없다.
         * 진짜 세션은 이 창을 넘지 않고, **새로 생긴 유령도 하루면 스스로 낫는다.**
         *
         * `charges`·`positions`에 최근 샘플이 들어왔는지 보는 편이 더 정밀하지만,
         * `positions.drive_id`에 인덱스가 있는지 확인되지 않아 3,000만 행을 잘못 훑을 수 있다.
         * 유령이 전부 8개월 이상 된 이 데이터에서는 시간 조건만으로 충분히 갈린다.
         *
         * `start_date`는 타임존 없는 컬럼에 든 UTC 값이라 `now() AT TIME ZONE 'UTC'`로 맞춘다 —
         * `now()`(timestamptz)와 그냥 비교하면 세션 타임존만큼(KST면 9시간) 창이 짧아진다.
         */
        private const val ACTIVITY_SQL = """
            SELECT EXISTS (SELECT 1
                             FROM charging_processes
                            WHERE end_date IS NULL
                              AND start_date >= (now() AT TIME ZONE 'UTC') - interval '24 hours') AS charging,
                   EXISTS (SELECT 1
                             FROM drives
                            WHERE end_date IS NULL
                              AND start_date >= (now() AT TIME ZONE 'UTC') - interval '24 hours') AS driving
        """

        private const val GEOFENCES_SQL = """
            SELECT g.name, g.latitude, g.longitude, g.radius
              FROM geofences g
        """
    }
}
