# TeslaMate 주행 인사이트(2단계) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `drives`·`geofences`·`cars`에서 온도별 전비·주행 시간대·거리 분포·자주 가는 곳 넷을 한 응답으로 내는 `GET /tesla/drive-insights?months=12`를 낸다.

**Architecture:** 1단계와 같은 차량 계층(`TeslaVehicleController`→`Service`→`Repository`→`JdbcTeslaVehicleRepository`)에 엔드포인트 하나를 더한다. **쿼리를 다섯 개로 나눈다** — 한 SQL에 CTE로 몰면 서로 다른 GROUP BY 넷을 UNION으로 붙였다가 다시 갈라 읽어야 한다. `drives`는 5,067행이라 다섯 번 훑어도 싸다. 집계는 SQL이 하고, 서비스는 행→DTO 변환과 **빈 버킷 자리 채움**만 한다.

**Tech Stack:** Kotlin 2.4.10, Spring Boot 4.1.0, `org.springframework.jdbc.core.simple.JdbcClient`, PostgreSQL(TeslaMate 보조 DataSource), kotest 6.2.2 `BehaviorSpec` + mockk 1.14.11

**Spec:** `docs/superpowers/specs/2026-08-17-tesla-battery-health-design.md` — **2단계 부분만**이다.

**앞선 작업:** 1단계(`GET /tesla/battery-health`)는 `main`에 머지됐다(`77a205c`). 이 계획은 그 위에서 시작한다.

## Global Constraints

- 대상 모듈은 `:daily-record`, 패키지는 `com.toy.backend.tesla`.
- **커밋 전 `./gradlew spotlessApply` 필수** (ktlint는 import 순서를 본다).
- **TeslaMate DB는 읽기만 한다.** 유일한 쓰기는 `TeslaChargeRepository.updateCost`다. 인덱스도 만들지 않는다 — 남의 스키마다.
- `TeslaVehicleService`에 **`@Transactional`을 붙이지 않는다.** 기본 트랜잭션 매니저는 daily-record 커넥션의 것이라 TeslaMate SQL에 효력이 없다.
- 조회 응답은 `DataResponseBody`. 잘못된 요청은 400 `ErrorCode.INVALID_REQUEST`(`CustomException`). **새 `Code` 구현 enum을 만들지 않는다.**
- **컨트롤러 단위 테스트를 쓰지 않는다.** 이 저장소에 `*ControllerTest.kt`가 하나도 없다.
- 시간대는 `Asia/Seoul`. TeslaMate는 UTC 값을 타임존 없는 `timestamp`에 넣는다. 요일·시각은 **KST로 옮긴 뒤** 뽑는다.
- **`now()`를 그냥 쓰지 않는다.** `end_date`는 타임존 없는 컬럼에 든 UTC 값이라 `now()`(timestamptz)와 그냥 비교하면 세션 타임존만큼(KST면 9시간) 창이 어긋난다. `(now() AT TIME ZONE 'UTC')`로 맞춘다 — `ACTIVITY_SQL`이 이미 같은 이유로 그렇게 한다.
- 소수는 `BigDecimal`. 반올림(소수 한 자리)은 **SQL의 `ROUND(...)`가 한다.**
- **나눗셈을 서버가 하지 않는다.** 전비는 앱이 `distanceKm ÷ (ratedRangeUsedKm × efficiencyKwhPerKm)`로 낸다. 1단계의 예외(중앙값)와 달리 여기는 합이라 나눌 이유가 없다.
- **`positions`를 건드리지 않는다.** 3,000만 행이다. 이 엔드포인트가 느려질 길이 없어야 한다.
- 차량이 1대라 `car_id`를 파라미터로도 응답으로도 두지 않는다.
- 테스트는 kotest `BehaviorSpec` + mockk. 격리 모드가 `InstancePerLeaf`라 각 `Given`은 자기 스텁을 스스로 준비한다.

## 1단계에서 배운 것 — 이 계획이 지키는 규칙

1단계에서 **「공통 WHERE가 두 지표의 조건을 섞는다」**가 리뷰에서 세 번 지적됐다. 하나의 CTE 공통 WHERE 위에 두 지표를 얹은 탓에, 어느 조건이 어느 지표의 것인지 코드에서 안 보였다.

**이 계획은 쿼리를 다섯 개로 나눠 그 문제를 구조적으로 피한다.** 각 쿼리는 **자기 지표가 실제로 쓰는 조건만** 건다:

| 쿼리 | 자기 조건 | 걸지 **않는** 것 |
|---|---|---|
| 온도별 전비 | `outside_temp_avg IS NOT NULL`, `ΔratedRange > 0` | — |
| 주행 시간대 | 없음 | 온도·주행가능거리 조건 (시간대는 둘 다 안 쓴다) |
| 거리 분포 | 없음 | 온도·주행가능거리 조건 |
| 자주 가는 곳 | `end_geofence_id IS NOT NULL`(JOIN) | 온도·주행가능거리 조건 |
| `cars.efficiency` | 없음 | 기간 창까지도 (차량 상수다) |

**모든 쿼리의 진짜 공통 조건은 셋뿐이다:** `end_date IS NOT NULL`, `distance > 0`, 기간 창.

리뷰어에게: 어떤 쿼리에 그 쿼리가 쓰지 않는 컬럼의 조건이 걸려 있으면 그것이 결함이다.

---

## 실측된 사실 (2026-08-17, 이 계획의 전제)

라즈베리파이의 실제 TeslaMate DB에서 확인했다.

| 항목 | 값 |
|---|---|
| `drives` 행 수 | **5,067** (완료·`distance > 0`인 것 5,055) |
| 최근 12개월 유효 주행 | **959** |
| `geofences` 행 수 | **0** |
| `end_geofence_id`가 있는 주행 | **0** |
| `outside_temp_avg`가 null인 주행 | **0** |
| `ΔratedRange <= 0`인 주행 | **447** (음수 16 + 정확히 0이 431) |
| 그 447건의 평균 거리 / 최대 거리 | **0.2km / 3.9km** |
| `cars` 행 수 | **1** (`model=3`, `trim_badging=74D`, `efficiency=0.1367`) |

컬럼 타입:

| 컬럼 | 타입 |
|---|---|
| `drives.distance` | `double precision` |
| `drives.duration_min` | `smallint` |
| `drives.outside_temp_avg` | `numeric` |
| `drives.start_rated_range_km`·`end_rated_range_km` | `numeric` |
| `drives.start_date`·`end_date` | `timestamp without time zone` |
| `drives.end_geofence_id` | `integer` |

**설계가 `double precision`을 가정한 곳이 여기서도 틀린다** — 1단계와 같다. `distance`만 `double precision`이고 나머지 수치는 `numeric`이다. `SUM(distance)`는 `::numeric`으로 올려 반올림하고, 주행가능거리 합은 이미 `numeric`이라 그대로 `ROUND`한다.

### 실측이 설계를 바꾸는 것 셋

**1. `places`는 오늘 항상 빈 배열이다.** `geofences`가 0개다. 판정 코드는 넣는다(TeslaMate에 지오펜스를 등록하는 순간 살아난다). **실데이터 검증이 오늘 불가능하다는 것을 알고 간다** — 2026-08-13 차량 요약 때 `locationName`이 같은 이유로 검증 못 됐던 것과 같은 자리다.

**2. `ΔratedRange <= 0` 제외는 거의 공짜다.** 447건이 빠지지만 **431건이 정확히 0**이고 평균 거리가 0.2km, 최대 3.9km다 — 주행가능거리 표시가 1km 단위로 움직이지 않을 만큼 짧은 주행이다. 설계가 「내리막 회생·주차 중 보정」을 이유로 들었는데, 실제로 지배적인 것은 **반올림으로 차이가 0이 되는 짧은 주행**이다. 결론(제외한다)은 그대로다 — 전비가 무한대가 되는 것을 막는 것이 목적이고, 거리 손실은 사실상 없다.

**3. 온도 버킷 다섯 개가 실제로 다 채워지고, 전비 차이가 눈에 보인다.** 최근 12개월:

| 버킷 | 주행 | 실주행 km | 소모 rated km | 비(소모÷실주행) |
|---|---|---|---|---|
| 영하 | 82 | 2,424.8 | 2,939.2 | **1.21** |
| 0~10 | 229 | 6,507.3 | 7,097.1 | 1.09 |
| 10~20 | 244 | 5,990.4 | 5,723.9 | **0.96** |
| 20~30 | 266 | 5,748.4 | 5,798.5 | 1.01 |
| 30 이상 | 118 | 2,494.6 | 2,551.5 | 1.02 |

영하에서 21% 더 쓰고 10~20℃에서 4% 덜 쓴다. **이 카드가 답하려던 질문에 실제로 답이 나온다.** 다섯 버킷이 다 차 있으므로 「빈 버킷 자리 채우기」는 이 데이터에서 발화하지 않는다 — 그래도 구현하고 단위 테스트로 덮는다(`months=1`이면 빌 수 있다).

**4. KST 변환이 맞는지 시간대 분포로 확인된다.** 최근 12개월 상위 칸이 월 08시(43)·화 17시(41)·월 17시(41)·화 08시(40)다 — 출퇴근이다. UTC로 뽑았으면 23시·08시로 찍혔을 것이다.

---

## 응답 형태

```json
{
  "data": {
    "months": 12,
    "efficiencyKwhPerKm": 0.1367,
    "temperatureBuckets": [
      { "fromC": null, "toC": 0,    "driveCount": 82,  "distanceKm": 2424.8, "ratedRangeUsedKm": 2939.2 },
      { "fromC": 0,    "toC": 10,   "driveCount": 229, "distanceKm": 6507.3, "ratedRangeUsedKm": 7097.1 },
      { "fromC": 10,   "toC": 20,   "driveCount": 244, "distanceKm": 5990.4, "ratedRangeUsedKm": 5723.9 },
      { "fromC": 20,   "toC": 30,   "driveCount": 266, "distanceKm": 5748.4, "ratedRangeUsedKm": 5798.5 },
      { "fromC": 30,   "toC": null, "driveCount": 118, "distanceKm": 2494.6, "ratedRangeUsedKm": 2551.5 }
    ],
    "driveTimes": [ { "weekday": 1, "hour": 8, "count": 43 } ],
    "distanceBuckets": [
      { "fromKm": 0,   "toKm": 5,    "driveCount": 62, "distanceKm": 180.2 },
      { "fromKm": 100, "toKm": null, "driveCount": 3,  "distanceKm": 412.0 }
    ],
    "places": []
  }
}
```

## 버킷 경계는 서버가 고정한다

| 온도(℃) | `fromC` | `toC` | | 거리(km) | `fromKm` | `toKm` |
|---|---|---|---|---|---|---|
| 영하 | null | 0 | | 0~5 | 0 | 5 |
| 0~10 | 0 | 10 | | 5~20 | 5 | 20 |
| 10~20 | 10 | 20 | | 20~50 | 20 | 50 |
| 20~30 | 20 | 30 | | 50~100 | 50 | 100 |
| 30 이상 | 30 | null | | 100 이상 | 100 | null |

- 하한/상한이 없으면 **null**이다. 경계는 **`from` 포함, `to` 미만**이다.
- **빈 버킷도 자리를 지킨다.** 다섯 개가 늘 온다. 비교하려고 보는 차트라 자리가 비면 축이 흔들린다.
- **빈 버킷의 숫자는 0이지 null이 아니다.** `MonthlyStat`이 「기록이 없다」를 null로 내는 것과 반대인데, 뜻이 다르기 때문이다 — 여기서 0은 「그 온도대에 실제로 안 탔다」는 사실이고, 기간 창 안의 모든 주행이 반드시 어느 한 버킷에 들어가므로 「기록이 없다」와 헷갈릴 자리가 없다.
- **`driveTimes`는 자리를 채우지 않는다.** 168칸 중 대부분이 0이고, 히트맵은 없는 칸을 빈칸으로 그리면 된다.

**경계 숫자가 SQL의 `CASE`와 Kotlin의 버킷 목록 두 곳에 나온다.** 피할 수 없다 — SQL은 임계값으로, Kotlin은 응답 라벨로 필요하다. 양쪽 주석이 서로를 가리키게 한다.

---

### Task 1: 행 타입·리포지토리 인터페이스와 온도 버킷 SQL

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleRows.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleRepository.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/JdbcTeslaVehicleRepository.kt`

**Interfaces:**
- Consumes: 없음
- Produces:
  - `DriveTemperatureBucketRow(bucket: Int, driveCount: Int, distanceKm: BigDecimal, ratedRangeUsedKm: BigDecimal)`
  - `DriveTimeRow(weekday: Int, hour: Int, count: Int)`
  - `DriveDistanceBucketRow(bucket: Int, driveCount: Int, distanceKm: BigDecimal)`
  - `DrivePlaceRow(name: String, driveCount: Int, distanceKm: BigDecimal)`
  - `TeslaVehicleRepository.driveTemperatureBuckets(months: Int): List<DriveTemperatureBucketRow>`
  - `TeslaVehicleRepository.driveTimes(months: Int): List<DriveTimeRow>`
  - `TeslaVehicleRepository.driveDistanceBuckets(months: Int): List<DriveDistanceBucketRow>`
  - `TeslaVehicleRepository.drivePlaces(months: Int): List<DrivePlaceRow>`
  - `TeslaVehicleRepository.carEfficiency(): BigDecimal?`
  - JDBC 구현: `driveTemperatureBuckets`·`carEfficiency` 둘만 (나머지 셋은 Task 2)

**이 태스크에 새 단위 테스트가 없다** — 이 저장소는 SQL을 단위 테스트로 검증하지 않는다(Task 5에서 실 DB로 확인한다). 검증은 「컴파일이 되고 기존 테스트가 그대로 초록인가」다.

- [ ] **Step 1: 행 타입 넷을 더한다**

`TeslaVehicleRows.kt` 맨 끝(`BatteryHealthMonthRow` 아래)에 붙인다. 파일 상단은 이미 `java.math.BigDecimal`을 import 한다.

```kotlin
/*
 * 아래 넷은 `/tesla/drive-insights`의 행 타입이다. **집계는 전부 SQL이 하고 여기 오는 것은
 * 이미 합쳐진 값이다** — 서비스가 하는 일은 버킷 자리를 채우고 DTO로 옮기는 것뿐이다.
 */

/**
 * 온도 버킷 하나의 합. `bucket`은 1..5이고 그 경계는 `TeslaVehicleService.TEMPERATURE_BUCKETS`가
 * 라벨로 갖는다 — **SQL의 `CASE`와 그 목록이 같은 숫자를 써야 한다.**
 *
 * `ratedRangeUsedKm`은 `start_rated_range_km - end_rated_range_km`의 합이다. kWh 환산
 * (`× cars.efficiency`)과 전비 나눗셈은 앱이 한다.
 */
data class DriveTemperatureBucketRow(
    val bucket: Int,
    val driveCount: Int,
    val distanceKm: BigDecimal,
    val ratedRangeUsedKm: BigDecimal,
)

/** `weekday`는 **0이 일요일**이다(PostgreSQL `dow` 그대로). KST 기준으로 뽑는다. */
data class DriveTimeRow(
    val weekday: Int,
    val hour: Int,
    val count: Int,
)

/**
 * 거리 버킷 하나의 합. `bucket`은 1..5이고 경계는
 * `TeslaVehicleService.DISTANCE_BUCKETS`가 갖는다.
 */
data class DriveDistanceBucketRow(
    val bucket: Int,
    val driveCount: Int,
    val distanceKm: BigDecimal,
)

/** 도착 지오펜스별 합. **주소는 내지 않는다** — 이름을 붙인 곳만 센다. */
data class DrivePlaceRow(
    val name: String,
    val driveCount: Int,
    val distanceKm: BigDecimal,
)
```

- [ ] **Step 2: 리포지토리 인터페이스에 메서드 다섯을 더한다**

`TeslaVehicleRepository.kt`의 `batteryHealthMonthly()` 아래에 붙인다. 파일 상단에 `import java.math.BigDecimal`을 더해야 한다(지금은 `java.time.LocalDateTime`만 있다).

```kotlin
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
     * 도착 지오펜스별 주행 합, 건수 많은 순 상위 10개.
     *
     * **지오펜스가 없는 도착지는 아예 세지 않는다** — `/tesla/status`가 좌표와 주소를 싣지 않는
     * 방침과 같다. 이 DB에는 지오펜스가 0개라 **오늘은 항상 빈 리스트다.**
     */
    fun drivePlaces(months: Int): List<DrivePlaceRow>

    /**
     * `cars.efficiency`(kWh/km) 그대로. 차량이 1대라 파라미터가 없다.
     *
     * **null일 수 있다** — TeslaMate가 아직 못 채운 경우다. 그때 앱은 전비 카드를 감춘다.
     * 차량 상수라 기간 창을 걸지 않는다.
     */
    fun carEfficiency(): BigDecimal?
```

- [ ] **Step 3: 온도 버킷과 `carEfficiency`의 JDBC 구현을 더한다**

`JdbcTeslaVehicleRepository.kt`의 `batteryHealthMonthly()` 아래에 붙인다. 파일 상단에 `import java.math.BigDecimal`을 더해야 한다.

```kotlin
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

    override fun carEfficiency(): BigDecimal? =
        teslaMateJdbcClient
            .sql(CAR_EFFICIENCY_SQL)
            .query { rs, _ -> rs.getBigDecimal("efficiency") }
            .optional()
            .orElse(null)
```

`.optional()`은 행이 없을 때 비고, `getBigDecimal`은 SQL NULL에 null을 준다 — 차가 없어도 `efficiency`가 비어도 결과는 null이다. `findOpenState()`가 쓰는 것과 같은 꼴이다.

- [ ] **Step 4: 두 SQL 상수를 더한다**

같은 파일 `companion object` 안, `BATTERY_HEALTH_MONTHLY_SQL` 아래에 붙인다.

```kotlin
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
```

- [ ] **Step 5: 컴파일과 기존 테스트가 초록인지 확인한다**

Run: `./gradlew spotlessApply :daily-record:test`
Expected: BUILD SUCCESSFUL. 새 테스트는 없고 기존 테스트가 그대로 통과해야 한다. `TeslaVehicleRepository`를 구현한 클래스가 `JdbcTeslaVehicleRepository` 하나뿐인지 확인해라 — 다른 구현체가 있으면 아직 안 만든 세 메서드 때문에 컴파일이 깨진다. 그 경우 **BLOCKED로 보고해라**(Task 2가 그 셋을 만든다).

`driveTimes`·`driveDistanceBuckets`·`drivePlaces`는 인터페이스에만 있고 구현이 없으므로 **`JdbcTeslaVehicleRepository`가 컴파일되지 않는다.** 그래서 이 태스크에서 세 메서드의 **본문만 `TODO()`로 둔다:**

```kotlin
    override fun driveTimes(months: Int): List<DriveTimeRow> = TODO("Task 2")

    override fun driveDistanceBuckets(months: Int): List<DriveDistanceBucketRow> = TODO("Task 2")

    override fun drivePlaces(months: Int): List<DrivePlaceRow> = TODO("Task 2")
```

**Task 2가 이 셋을 반드시 채운다.** `TODO()`가 남은 채로 브랜치가 끝나면 그것이 결함이다.

- [ ] **Step 6: 커밋**

```bash
git add apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleRows.kt \
        apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleRepository.kt \
        apps/daily-record/src/main/kotlin/com/toy/backend/tesla/JdbcTeslaVehicleRepository.kt
git commit -m "feat: 온도 버킷별 주행 합을 drives에서 집계한다"
```

---

### Task 2: 시간대·거리 분포·자주 가는 곳 SQL

Task 1이 `TODO()`로 남긴 세 메서드를 채운다.

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/JdbcTeslaVehicleRepository.kt`

**Interfaces:**
- Consumes: Task 1의 `DriveTimeRow`·`DriveDistanceBucketRow`·`DrivePlaceRow`, `DRIVE_WINDOW` 상수
- Produces: 위 세 메서드의 동작 구현 (시그니처는 Task 1이 이미 정했다)

**이 태스크에도 새 단위 테스트가 없다.** 검증은 컴파일 + 기존 테스트 초록이다.

- [ ] **Step 1: 세 `TODO()`를 구현으로 바꾼다**

```kotlin
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
```

- [ ] **Step 2: 세 SQL 상수를 더한다**

`companion object` 안, `CAR_EFFICIENCY_SQL` 아래에 붙인다.

```kotlin
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
         * 시각은 `start_date`로 뽑지만 기간 창은 `end_date`로 건다 — 창에 드는 기준을 네 쿼리가
         * 같이 쓰게 하려는 것이고, 자정을 걸친 주행 한 건의 차이일 뿐이다.
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
         * `end_geofence_id`로 묶어 이름을 낸다. **주소는 내지 않는다** — 지오펜스가 없는
         * 도착지는 `JOIN`에서 아예 빠진다. `/tesla/status`가 좌표와 주소를 싣지 않는 방침과 같다.
         *
         * **이 DB에는 `geofences`가 0행이라 오늘은 항상 빈 결과다.** 등록하는 순간 살아난다.
         *
         * 이름이 아니라 **id로 묶는다** — 같은 이름의 지오펜스가 둘일 수 있다.
         *
         * `geofences`는 수십 행이고 `drives`는 수천 행이라 해시 조인으로 즉시 끝난다.
         */
        private const val DRIVE_PLACES_SQL = """
            SELECT g.name                            AS name,
                   COUNT(*)                          AS drive_count,
                   ROUND(SUM(d.distance)::numeric, 1) AS distance_km
              FROM drives d
              JOIN geofences g ON g.id = d.end_geofence_id
             WHERE d.end_date IS NOT NULL
               AND d.distance > 0
               $DRIVE_WINDOW
             GROUP BY g.id, g.name
             ORDER BY drive_count DESC, distance_km DESC
             LIMIT 10
        """
```

- [ ] **Step 3: `TODO()`가 하나도 남지 않았는지 확인한다**

Run:

```bash
grep -n 'TODO(' apps/daily-record/src/main/kotlin/com/toy/backend/tesla/JdbcTeslaVehicleRepository.kt
```

Expected: 출력 없음. 하나라도 남으면 이 태스크가 안 끝난 것이다.

- [ ] **Step 4: 컴파일과 기존 테스트가 초록인지 확인한다**

Run: `./gradlew spotlessApply :daily-record:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: 커밋**

```bash
git add apps/daily-record/src/main/kotlin/com/toy/backend/tesla/JdbcTeslaVehicleRepository.kt
git commit -m "feat: 주행 시간대·거리 분포·자주 가는 곳을 집계한다"
```

---

### Task 3: 응답 DTO와 서비스 — 버킷 자리를 채운다

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleDtos.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleService.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/tesla/TeslaVehicleServiceTest.kt`

**Interfaces:**
- Consumes: Task 1·2의 네 행 타입과 다섯 리포지토리 메서드
- Produces:
  - `TeslaDriveInsightsResponse(months: Int, efficiencyKwhPerKm: BigDecimal?, temperatureBuckets: List<TemperatureBucket>, driveTimes: List<DriveTime>, distanceBuckets: List<DistanceBucket>, places: List<DrivePlace>)`
  - `TemperatureBucket(fromC: Int?, toC: Int?, driveCount: Int, distanceKm: BigDecimal, ratedRangeUsedKm: BigDecimal)`
  - `DriveTime(weekday: Int, hour: Int, count: Int)`
  - `DistanceBucket(fromKm: Int, toKm: Int?, driveCount: Int, distanceKm: BigDecimal)`
  - `DrivePlace(name: String, driveCount: Int, distanceKm: BigDecimal)`
  - `TeslaVehicleService.driveInsights(months: Int): TeslaDriveInsightsResponse`
  - `TeslaVehicleService.MIN_MONTHS = 1`, `MAX_MONTHS = 60`, `DEFAULT_MONTHS = 12`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`TeslaVehicleServiceTest.kt`의 마지막 `Given` 블록 뒤, 닫는 `})` 앞에 붙인다. 파일 상단은 이미 `BigDecimal`·`shouldThrow`·`CustomException`·`ErrorCode`를 import 한다.

```kotlin
        // 서비스가 하는 일은 버킷 자리 채움과 행→DTO 변환뿐이다. 합계·KST 변환은 SQL이 한다.
        Given("주행 인사이트를 조회할 때 리포지토리가 아무 행도 주지 않으면") {
            every { vehicleRepository.driveTemperatureBuckets(any()) } returns emptyList()
            every { vehicleRepository.driveTimes(any()) } returns emptyList()
            every { vehicleRepository.driveDistanceBuckets(any()) } returns emptyList()
            every { vehicleRepository.drivePlaces(any()) } returns emptyList()
            every { vehicleRepository.carEfficiency() } returns BigDecimal("0.1367")

            val response = service.driveInsights(12)

            // 비교하려고 보는 차트라 자리가 비면 축이 흔들린다. 빈 버킷도 다섯 개가 온다.
            Then("온도 버킷 다섯 개가 자리를 지킨다") {
                response.temperatureBuckets.size shouldBe 5
                response.temperatureBuckets.map { it.fromC } shouldBe listOf(null, 0, 10, 20, 30)
                response.temperatureBuckets.map { it.toC } shouldBe listOf(0, 10, 20, 30, null)
                response.temperatureBuckets.all { it.driveCount == 0 } shouldBe true
            }

            // 빈 버킷의 숫자는 0이다. null이 아니다 — 「그 온도대에 안 탔다」는 사실이지
            // 「기록이 없다」가 아니다.
            Then("빈 버킷의 합은 0이다") {
                response.temperatureBuckets.first().distanceKm shouldBe BigDecimal.ZERO
                response.temperatureBuckets.first().ratedRangeUsedKm shouldBe BigDecimal.ZERO
            }

            Then("거리 버킷 다섯 개가 자리를 지킨다") {
                response.distanceBuckets.map { it.fromKm } shouldBe listOf(0, 5, 20, 50, 100)
                response.distanceBuckets.map { it.toKm } shouldBe listOf(5, 20, 50, 100, null)
                response.distanceBuckets.all { it.driveCount == 0 } shouldBe true
            }

            // 168칸 중 대부분이 0이라 히트맵은 없는 칸을 빈칸으로 그린다. 자리를 채우지 않는다.
            Then("driveTimes는 자리를 채우지 않는다") {
                response.driveTimes shouldBe emptyList()
            }

            // 이 DB에는 지오펜스가 0개다. 빈 배열이어야 한다(null이 아니다).
            Then("places가 빈 배열이다") {
                response.places shouldBe emptyList()
            }

            Then("months를 되돌려 실어 앱이 무엇을 받았는지 알 수 있다") {
                response.months shouldBe 12
            }
        }

        Given("일부 버킷에만 주행이 있을 때") {
            every { vehicleRepository.driveTemperatureBuckets(any()) } returns
                listOf(
                    DriveTemperatureBucketRow(1, 82, BigDecimal("2424.8"), BigDecimal("2939.2")),
                    DriveTemperatureBucketRow(5, 118, BigDecimal("2494.6"), BigDecimal("2551.5")),
                )
            every { vehicleRepository.driveTimes(any()) } returns
                listOf(DriveTimeRow(1, 8, 43), DriveTimeRow(2, 17, 41))
            every { vehicleRepository.driveDistanceBuckets(any()) } returns
                listOf(DriveDistanceBucketRow(5, 3, BigDecimal("412.0")))
            every { vehicleRepository.drivePlaces(any()) } returns
                listOf(DrivePlaceRow("집", 124, BigDecimal("812.4")))
            every { vehicleRepository.carEfficiency() } returns BigDecimal("0.1367")

            val response = service.driveInsights(12)

            Then("온 버킷은 값이 실리고 안 온 버킷은 0으로 채워진다") {
                val below = response.temperatureBuckets.first { it.fromC == null }
                below.driveCount shouldBe 82
                below.distanceKm shouldBe BigDecimal("2424.8")
                below.ratedRangeUsedKm shouldBe BigDecimal("2939.2")

                val middle = response.temperatureBuckets.first { it.fromC == 10 }
                middle.driveCount shouldBe 0
                middle.distanceKm shouldBe BigDecimal.ZERO
            }

            Then("거리 버킷도 같은 방식으로 채워진다") {
                response.distanceBuckets.first { it.fromKm == 100 }.driveCount shouldBe 3
                response.distanceBuckets.first { it.fromKm == 0 }.driveCount shouldBe 0
            }

            // dow는 0이 일요일이다. 서버가 번역하지 않고 그대로 올린다.
            Then("driveTimes는 온 것만 그대로 나간다") {
                response.driveTimes.size shouldBe 2
                response.driveTimes.first().weekday shouldBe 1
                response.driveTimes.first().hour shouldBe 8
                response.driveTimes.first().count shouldBe 43
            }

            Then("places는 온 것만 그대로 나간다") {
                response.places.single().name shouldBe "집"
                response.places.single().driveCount shouldBe 124
            }
        }

        // TeslaMate가 efficiency를 아직 못 채운 경우다. 그때 앱은 전비 카드를 감춘다.
        Given("cars.efficiency가 null일 때") {
            every { vehicleRepository.driveTemperatureBuckets(any()) } returns emptyList()
            every { vehicleRepository.driveTimes(any()) } returns emptyList()
            every { vehicleRepository.driveDistanceBuckets(any()) } returns emptyList()
            every { vehicleRepository.drivePlaces(any()) } returns emptyList()
            every { vehicleRepository.carEfficiency() } returns null

            val response = service.driveInsights(12)

            Then("efficiencyKwhPerKm이 null이다") {
                response.efficiencyKwhPerKm shouldBe null
            }
        }

        Given("months가 범위 경계일 때") {
            every { vehicleRepository.driveTemperatureBuckets(any()) } returns emptyList()
            every { vehicleRepository.driveTimes(any()) } returns emptyList()
            every { vehicleRepository.driveDistanceBuckets(any()) } returns emptyList()
            every { vehicleRepository.drivePlaces(any()) } returns emptyList()
            every { vehicleRepository.carEfficiency() } returns null

            Then("1과 60은 통과한다") {
                service.driveInsights(1).months shouldBe 1
                service.driveInsights(60).months shouldBe 60
            }

            Then("0과 61은 400이다") {
                shouldThrow<CustomException> { service.driveInsights(0) }
                    .errorCode shouldBe ErrorCode.INVALID_REQUEST
                shouldThrow<CustomException> { service.driveInsights(61) }
                    .errorCode shouldBe ErrorCode.INVALID_REQUEST
            }
        }
```

프로퍼티 이름은 **`errorCode`**다(`CustomException(val errorCode: Code, vararg val params: Any?)`). `code`가 아니다.

- [ ] **Step 2: 실패하는지 돌린다**

Run: `./gradlew :daily-record:test --tests '*TeslaVehicleServiceTest*'`
Expected: 컴파일 실패 — `Unresolved reference: driveInsights`. 아직 서비스에 메서드가 없으므로 이 실패가 맞다.

- [ ] **Step 3: 응답 DTO를 더한다**

`TeslaVehicleDtos.kt` 맨 끝에 붙인다. 파일 상단은 이미 `java.math.BigDecimal`을 import 한다.

```kotlin
/**
 * 주행 인사이트 네 카드. **한 화면이 넷을 함께 그리므로 한 응답에 싣는다** — 나누면 같은 화면이
 * 네 번 부르고 그중 셋은 나머지 하나를 기다린다(`/tesla/summary`가 목록과 합계를 함께 싣는 것과
 * 같은 이유다).
 *
 * **나눗셈은 앱이 한다.** 서버는 버킷별 합만 낸다 — 전비는
 * `distanceKm ÷ (ratedRangeUsedKm × efficiencyKwhPerKm)`이고, 분모가 0인 버킷 처리는 화면이
 * 정한다. 1단계의 예외(중앙값)와 달리 여기는 합이라 나눌 이유가 없다.
 */
data class TeslaDriveInsightsResponse(
    /** 받은 창을 되돌려 싣는다 — 앱이 무엇을 받았는지 알 수 있게. */
    val months: Int,
    /**
     * `cars.efficiency` 그대로(kWh/km). **null일 수 있다** — TeslaMate가 아직 못 채운
     * 경우다. 그때 앱은 전비 카드를 감춘다.
     */
    val efficiencyKwhPerKm: BigDecimal?,
    /** 다섯 개가 늘 온다. 빈 버킷도 자리를 지킨다. */
    val temperatureBuckets: List<TemperatureBucket>,
    /** **0인 칸은 빠진다.** 168칸 중 대부분이 0이라 히트맵이 빈칸으로 그리면 된다. */
    val driveTimes: List<DriveTime>,
    /** 다섯 개가 늘 온다. */
    val distanceBuckets: List<DistanceBucket>,
    /** 지오펜스를 붙인 도착지만, 건수 많은 순 상위 10개. **주소는 내지 않는다.** */
    val places: List<DrivePlace>,
)

/**
 * 하한/상한이 없으면 null이다(`fromC: null`이 영하, `toC: null`이 30 이상).
 * 경계는 **`fromC` 포함, `toC` 미만**이다.
 *
 * **빈 버킷의 숫자는 0이지 null이 아니다.** `MonthlyStat`이 「기록이 없다」를 null로 내는 것과
 * 반대인데 뜻이 다르기 때문이다 — 여기서 0은 「그 온도대에 실제로 안 탔다」는 사실이고, 창 안의
 * 모든 주행이 반드시 어느 한 버킷에 들어가므로 「기록이 없다」와 헷갈릴 자리가 없다.
 */
data class TemperatureBucket(
    val fromC: Int?,
    val toC: Int?,
    /** `outside_temp_avg`가 없거나 주행가능거리 소모가 0 이하인 주행은 빠진 뒤의 건수다. */
    val driveCount: Int,
    val distanceKm: BigDecimal,
    /** `start_rated_range_km - end_rated_range_km`의 합. kWh 환산은 앱이 한다. */
    val ratedRangeUsedKm: BigDecimal,
)

/** `weekday`는 **0이 일요일**이다(PostgreSQL `dow` 그대로). 시각은 KST다. */
data class DriveTime(
    val weekday: Int,
    val hour: Int,
    val count: Int,
)

/** `toKm`이 null이면 상한이 없다는 뜻이다. 경계는 **`fromKm` 포함, `toKm` 미만**이다. */
data class DistanceBucket(
    val fromKm: Int,
    val toKm: Int?,
    val driveCount: Int,
    val distanceKm: BigDecimal,
)

data class DrivePlace(
    val name: String,
    val driveCount: Int,
    val distanceKm: BigDecimal,
)
```

- [ ] **Step 4: 서비스 메서드와 버킷 목록을 더한다**

`TeslaVehicleService.kt`의 `batteryHealth()` 아래, `private fun resolveState(...)` 위에 붙인다.

```kotlin
    /**
     * 쿼리 다섯 번이 전부다. **한 SQL에 몰지 않는다** — 서로 다른 GROUP BY 넷을 UNION으로
     * 붙였다가 다시 갈라 읽어야 한다. `drives`는 5,000행대라 다섯 번 훑어도 싸다.
     *
     * 서비스가 하는 일은 **빈 버킷 자리 채움과 행→DTO 변환뿐**이다. 합계·KST 변환·정렬은
     * SQL이 한다.
     */
    fun driveInsights(months: Int): TeslaDriveInsightsResponse {
        if (months !in MIN_MONTHS..MAX_MONTHS) {
            throw CustomException(ErrorCode.INVALID_REQUEST, "months는 $MIN_MONTHS~$MAX_MONTHS 사이여야 합니다")
        }

        val temperatures = vehicleRepository.driveTemperatureBuckets(months).associateBy { it.bucket }
        val distances = vehicleRepository.driveDistanceBuckets(months).associateBy { it.bucket }

        return TeslaDriveInsightsResponse(
            months = months,
            efficiencyKwhPerKm = vehicleRepository.carEfficiency(),
            temperatureBuckets =
                TEMPERATURE_BUCKETS.map { (bucket, bounds) ->
                    val row = temperatures[bucket]
                    TemperatureBucket(
                        fromC = bounds.first,
                        toC = bounds.second,
                        driveCount = row?.driveCount ?: 0,
                        distanceKm = row?.distanceKm ?: BigDecimal.ZERO,
                        ratedRangeUsedKm = row?.ratedRangeUsedKm ?: BigDecimal.ZERO,
                    )
                },
            driveTimes =
                vehicleRepository.driveTimes(months).map {
                    DriveTime(weekday = it.weekday, hour = it.hour, count = it.count)
                },
            distanceBuckets =
                DISTANCE_BUCKETS.map { (bucket, bounds) ->
                    val row = distances[bucket]
                    DistanceBucket(
                        fromKm = bounds.first,
                        toKm = bounds.second,
                        driveCount = row?.driveCount ?: 0,
                        distanceKm = row?.distanceKm ?: BigDecimal.ZERO,
                    )
                },
            places =
                vehicleRepository.drivePlaces(months).map {
                    DrivePlace(name = it.name, driveCount = it.driveCount, distanceKm = it.distanceKm)
                },
        )
    }
```

`companion object`의 `TREND_MONTHS` 아래에 상수를 더한다:

```kotlin
        /** `/tesla/drive-insights`의 창. 기본 12개월, 1~60. */
        const val MIN_MONTHS = 1
        const val MAX_MONTHS = 60
        const val DEFAULT_MONTHS = 12

        /**
         * 온도 버킷의 **응답 라벨**이다(℃). `bucket` 번호 → (`fromC`, `toC`).
         * 하한/상한이 없으면 null이고, 경계는 `from` 포함·`to` 미만이다.
         *
         * **`JdbcTeslaVehicleRepository.DRIVE_TEMPERATURE_BUCKETS_SQL`의 `CASE`와 같은 숫자여야
         * 한다** — 거기는 임계값으로, 여기는 라벨로 쓴다. 한쪽만 고치면 응답의 라벨과 실제
         * 집계가 어긋난다. 다섯 개인 이유는 계절이 갈리는 최소 단위라서고, 앱이 버킷을 정하면
         * 서버가 원자료를 통째로 보내야 한다.
         */
        private val TEMPERATURE_BUCKETS: List<Pair<Int, Pair<Int?, Int?>>> =
            listOf(
                1 to (null to 0),
                2 to (0 to 10),
                3 to (10 to 20),
                4 to (20 to 30),
                5 to (30 to null),
            )

        /**
         * 거리 버킷의 **응답 라벨**이다(km). `bucket` 번호 → (`fromKm`, `toKm`).
         *
         * **`JdbcTeslaVehicleRepository.DRIVE_DISTANCE_BUCKETS_SQL`의 `CASE`와 같은 숫자여야
         * 한다.**
         */
        private val DISTANCE_BUCKETS: List<Pair<Int, Pair<Int, Int?>>> =
            listOf(
                1 to (0 to 5),
                2 to (5 to 20),
                3 to (20 to 50),
                4 to (50 to 100),
                5 to (100 to null),
            )
```

파일 상단에 `import java.math.BigDecimal`을 더해야 한다.

- [ ] **Step 5: 통과하는지 돌린다**

Run: `./gradlew spotlessApply :daily-record:test --tests '*TeslaVehicleServiceTest*'`
Expected: PASS. 새로 더한 `Then`이 전부 초록이어야 한다.

- [ ] **Step 6: 전체 테스트를 한 번 돌린다**

Run: `./gradlew :daily-record:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: 커밋**

```bash
git add apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleDtos.kt \
        apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleService.kt \
        apps/daily-record/src/test/kotlin/com/toy/backend/tesla/TeslaVehicleServiceTest.kt
git commit -m "feat: 주행 인사이트 네 카드를 한 응답으로 낸다"
```

---

### Task 4: 엔드포인트 배선 — `GET /tesla/drive-insights`

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleController.kt`

**Interfaces:**
- Consumes: `TeslaVehicleService.driveInsights(months)`, `TeslaVehicleService.DEFAULT_MONTHS`
- Produces: `GET /tesla/drive-insights?months=12` → 200 `DataResponseBody<TeslaDriveInsightsResponse>`

- [ ] **Step 1: 클래스 KDoc과 `@Tag` 설명을 갱신한다**

지금 KDoc은 차량 쪽을 셋으로 적고 있다. 넷이 된다.

기존:

```kotlin
 * 충전(`/tesla/charges` 하위)과 차량(`/tesla/summary`·`/tesla/status`·`/tesla/battery-health`)을
 * 갈라 둔다 —
```

바꾼 뒤:

```kotlin
 * 충전(`/tesla/charges` 하위)과 차량(`/tesla/summary`·`/tesla/status`·`/tesla/battery-health`·
 * `/tesla/drive-insights`)을 갈라 둔다 —
```

`@Tag`의 `description`도 「TeslaMate 차량 요약·상태·배터리 건강 API」에서 주행 인사이트가 드러나게 고친다.

- [ ] **Step 2: 매핑을 더한다**

`batteryHealth()` 아래에 붙인다.

```kotlin
    /**
     * **네 카드를 한 응답에 싣는다.** 나누면 같은 화면이 네 번 부르고 그중 셋은 나머지 하나를
     * 기다린다. `months`는 응답에 되돌려 실어 앱이 무엇을 받았는지 알 수 있게 한다.
     */
    @GetMapping("/drive-insights")
    @Operation(summary = "주행 인사이트 — 온도별 전비·주행 시간대·거리 분포·자주 가는 곳")
    fun driveInsights(
        @Parameter(description = "거슬러 볼 개월 수(1~60)", example = "12")
        // `defaultValue`는 애노테이션 인자라 **리터럴이어야 한다.**
        // `TeslaVehicleService.DEFAULT_MONTHS`와 같은 값을 유지한다 — 한쪽만 고치면
        // 파라미터를 생략했을 때의 창이 서비스가 아는 기본값과 어긋난다.
        @RequestParam(defaultValue = "12")
        months: Int,
    ): ResponseEntity<DataResponseBody<TeslaDriveInsightsResponse>> =
        ResponseEntity.ok(DataResponseBody(service.driveInsights(months)))
```

**범위 검증을 컨트롤러에 두지 않는다.** `months`가 1~60인지 보는 것은 서비스가 한다 — 단위 테스트가 닿는 자리가 거기이고, 이 저장소에는 컨트롤러 테스트가 없다.

- [ ] **Step 3: 전체 빌드가 초록인지 확인한다**

Run: `./gradlew spotlessApply build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: 새 심볼이 응답까지 실렸는지 대 본다** (AGENTS.md 「커밋 전」 검사)

Run:

```bash
for name in ratedRangeUsedKm efficiencyKwhPerKm temperatureBuckets distanceBuckets driveTimes places; do
  echo "=== $name ==="; grep -rln "$name" --include='*.kt' .
done
```

Expected: 각 목록에 **`TeslaVehicleDtos.kt`가 반드시 있어야 한다** — 없으면 그 값은 앱까지 가지 않는다.

- [ ] **Step 5: 커밋**

```bash
git add apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleController.kt
git commit -m "feat: 주행 인사이트를 GET /tesla/drive-insights로 연다"
```

---

### Task 5: 실 DB에서 다섯 SQL을 눈으로 확인한다

단위 테스트는 리포지토리를 목으로 대체하므로 **SQL 자체는 검증되지 않는다.** 접속은 Docker psql로 한다(로컬에 `psql`이 없다). 접속 정보는 컨트롤러가 준다.

**Files:** 없음 (읽기만 한다)

**Interfaces:**
- Consumes: Task 1·2의 다섯 SQL 상수
- Produces: 실측 숫자 — Task 6의 changelog가 쓴다

- [ ] **Step 1: 다섯 SQL을 코드에서 그대로 가져와 돌린다**

`JdbcTeslaVehicleRepository.kt`의 상수 원문을 읽어 `:months`만 `12`로 바꿔 돌려라. **브리프에 적힌 것을 손으로 옮겨 적지 말고 실제 코드에 있는 것을 돌려야 의미가 있다.**

Expected(2026-08-17 기준값, 창이 굴러가므로 조금씩 다를 수 있다):

| 쿼리 | 기대 |
|---|---|
| 온도 버킷 | 다섯 행. 영하 82건·2424.8km·2939.2 rated, 30이상 118건·2494.6km·2551.5 rated 근처 |
| 시간대 | 상위가 월(1) 08시·화(2) 17시·월 17시·화 08시. 출퇴근이 보여야 한다 |
| 거리 분포 | 다섯 행 또는 그 이하. 합계 주행 건수가 959 근처 |
| 자주 가는 곳 | **빈 결과** — `geofences`가 0행이다 |
| `cars.efficiency` | `0.1367` |

- [ ] **Step 2: 창이 KST가 아니라 UTC 기준으로 잘렸는지 확인한다**

```sql
SELECT (now() AT TIME ZONE 'UTC')                              AS boundary_base_utc,
       now()                                                   AS now_tstz,
       (now() AT TIME ZONE 'UTC') - now()                       AS 차이
```

Expected: `boundary_base_utc`가 `now_tstz`의 UTC 표현이다. 세션 타임존이 무엇이든 `end_date`(UTC 값)와 같은 축에서 비교된다는 것을 눈으로 확인하는 단계다.

- [ ] **Step 3: 시간대가 KST로 뽑혔는지 UTC와 나란히 본다**

```sql
SELECT EXTRACT(hour FROM d.start_date)::int                                            AS hour_utc,
       EXTRACT(hour FROM d.start_date AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Seoul')::int AS hour_kst,
       count(*)
  FROM drives d
 WHERE d.end_date IS NOT NULL AND d.distance > 0
   AND d.end_date >= (now() AT TIME ZONE 'UTC') - (12 * interval '1 month')
 GROUP BY hour_utc, hour_kst
 ORDER BY count DESC
 LIMIT 5
```

Expected: `hour_kst`가 `hour_utc + 9`(24로 나눈 나머지)이고, 상위 칸의 `hour_kst`가 08·17 같은 출퇴근 시각이다. `hour_utc`로는 23시·08시로 찍힌다 — **이것이 KST 변환이 값을 하는 자리다.**

- [ ] **Step 4: 버킷 경계가 SQL과 Kotlin에서 같은 숫자인지 대조한다**

Run:

```bash
grep -n 'THEN 1\|THEN 2\|THEN 3\|THEN 4\|ELSE 5' apps/daily-record/src/main/kotlin/com/toy/backend/tesla/JdbcTeslaVehicleRepository.kt
grep -n 'TEMPERATURE_BUCKETS\|DISTANCE_BUCKETS' -A 8 apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleService.kt
```

**눈이 아니라 대조로 확인한다** — AGENTS.md가 「SQL 컬럼 수와 `setX` 인덱스가 어긋난 것도 눈이 아니라 스크립트로 대조해서 잡았다」고 적은 자리다. 온도는 `0/10/20/30`, 거리는 `5/20/50/100`이 양쪽에 같은 순서로 있어야 한다.

- [ ] **Step 5: `months` 경계가 실제로 도는지 본다**

`months`를 1과 60으로 바꿔 온도 버킷 SQL을 두 번 돌려라.

Expected: `months=1`이면 행 수가 크게 줄고 **버킷이 다섯 개가 안 올 수 있다**(그때 서비스가 자리를 채운다). `months=60`이면 전 기간에 가깝다. 두 경우 모두 SQL이 오류 없이 돈다.

- [ ] **Step 6: 앱을 띄워 엔드포인트를 부른다 — 안 되면 안 된 이유를 적는다**

1단계에서 이 단계를 **못 했다.** 로컬에 `daily-record` DB·스키마는 있으나 로그인 계정 비밀번호가 없어 토큰을 못 받았다. 이번에도 같을 가능성이 높다.

시도는 하되 **환경을 새로 만들지 마라** — DB를 만들지도, 설정 파일을 고치지도, 인증을 우회하지도, 계정을 만들지도 마라. 안 되면 「실행 못 함」으로 적고 무엇이 없어서 못 했는지를 구체적으로 적어라. **이 한 단계 때문에 태스크를 BLOCKED로 내지 마라.**

- [ ] **Step 7: 실측 결과를 정리한다**

커밋할 코드는 없다. 위 단계의 실제 숫자(버킷별 건수·거리·rated 소모, 시간대 상위 칸, `places` 결과, `efficiency` 값, `months` 경계 동작)를 적어 Task 6으로 넘긴다.

---

### Task 6: changelog

**Files:**
- Create: `docs/changelog/2026-08-17-tesla-drive-insights.md`

**Interfaces:**
- Consumes: Task 5의 실측 숫자
- Produces: 없음

- [ ] **Step 1: 기존 changelog의 형식을 본다**

Run: `cat docs/changelog/2026-08-17-tesla-battery-health.md`
같은 절 구성과 문체를 따른다. 이 저장소는 **왜 그렇게 했는지**를 적는 곳이다.

- [ ] **Step 2: 항목을 쓴다**

담아야 하는 것:

- `GET /tesla/drive-insights?months=12`가 네 카드를 한 응답으로 낸다. **한 화면이 넷을 함께 그리므로** 나누지 않았다.
- **쿼리를 다섯 개로 나눈 이유**, 그리고 **1단계에서 배운 것을 어떻게 적용했는지** — 1단계는 하나의 공통 WHERE에 두 지표를 얹어 「어느 조건이 어느 지표 것인지」가 코드에서 안 보였고 리뷰에서 세 번 지적됐다. 여기서는 각 쿼리가 자기 지표가 쓰는 조건만 건다.
- **`now()`를 그냥 쓰지 않은 이유** — `end_date`는 타임존 없는 컬럼에 든 UTC 값이라 세션 타임존만큼 창이 어긋난다.
- **빈 버킷은 자리를 지키고 `driveTimes`는 안 지키는 이유** — 앞은 축이 흔들리고 뒤는 히트맵이라 빈칸으로 그리면 된다.
- **빈 버킷의 숫자가 0이지 null이 아닌 이유** — `MonthlyStat`과 반대인데, 여기서 0은 「그 온도대에 안 탔다」는 사실이다.
- **나눗셈을 앱에 맡긴 것** — 1단계의 중앙값 예외와 왜 다른지(여기는 합이다).
- **`places`가 오늘 항상 빈 배열이라는 것** — `geofences`가 0행이다. 실데이터 검증이 오늘 불가능하다.
- **`ΔratedRange <= 0` 제외의 실체** — 447건이 빠지지만 431건이 차이가 정확히 0이고 평균 거리 0.2km다. 설계는 「내리막 회생·주차 중 보정」을 이유로 들었으나 지배적인 것은 반올림이다.
- **온도별 전비 차이가 실제로 보인다는 것** — 영하 1.21 vs 10~20℃ 0.96.
- **컬럼 타입이 설계 가정과 다르다는 것** — `distance`만 `double precision`이고 나머지는 `numeric`이다(1단계와 같은 계열).
- Task 5의 실측 숫자.
- 이번에 하지 않은 것: 3단계 누적 합계·충전 곡선, 보류인 `positions`.
- Step 6(엔드포인트 실호출)을 했는지, 못 했으면 무엇이 없어서 못 했는지.

- [ ] **Step 3: 커밋**

```bash
git add docs/changelog/2026-08-17-tesla-drive-insights.md
git commit -m "docs: 주행 인사이트를 changelog에 적는다"
```

---

## 이번 계획에서 하지 않는 것

- **3단계 누적 합계·충전 곡선** — `charges` 테이블을 새로 읽는다. 별건이다.
- **보류인 `positions`** — `positions.drive_id` 인덱스 확인이 선행 조건이다. 없으면 주행 하나를 열 때마다 3,000만 행을 훑는다.
- **전비·잔존율 계산** — 나눗셈은 앱이 한다.
- **지오펜스 등록** — TeslaMate 쪽 설정이고 우리 코드가 할 일이 아니다. 등록되면 `places`가 저절로 살아난다.
