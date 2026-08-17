# TeslaMate 배터리 건강 집계(1단계) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `charging_processes` 한 테이블에서 월별 만충 환산 주행거리와 사용 가능 용량의 **중앙값**을 표본 수와 함께 내는 `GET /tesla/battery-health`를 낸다.

**Architecture:** 기존 `com.toy.backend.tesla`의 차량 계층(`TeslaVehicleController`→`Service`→`Repository`→`JdbcTeslaVehicleRepository`)에 엔드포인트 하나를 더한다. 새 테이블도, 새 파일도, 새 쓰기도 없다 — 여섯 파일에 각각 한 덩어리씩 붙는다. 집계(`percentile_cont`·월 그룹핑·KST 월 경계)는 전부 SQL이 하고, 서비스는 행→DTO 변환과 정렬만 한다.

**Tech Stack:** Kotlin 2.4.10, Spring Boot 4.1.0, `org.springframework.jdbc.core.simple.JdbcClient`, PostgreSQL(TeslaMate 보조 DataSource), kotest 6.2.2 `BehaviorSpec` + mockk 1.14.11

**Spec:** `docs/superpowers/specs/2026-08-17-tesla-battery-health-design.md`

**범위:** 설계 문서의 **1단계만**이다. 2단계(`/tesla/drive-insights`)·3단계(누적 합계·충전 곡선)·보류(`positions`)는 이 계획에 없다.

## Global Constraints

- 대상 모듈은 `:daily-record`, 패키지는 `com.toy.backend.tesla`.
- **커밋 전 `./gradlew spotlessApply` 필수** (ktlint는 import 순서를 본다).
- **TeslaMate DB는 읽기만 한다.** 유일한 쓰기는 `TeslaChargeRepository.updateCost`(`charging_processes.cost`)이고 이 계획은 그것을 건드리지 않는다. 인덱스도 만들지 않는다 — 남의 스키마다.
- 조회 응답은 `DataResponseBody`로 감싼다. 새 `Code` 구현 enum을 만들지 않는다.
- `TeslaVehicleService`에 **`@Transactional`을 붙이지 않는다.** 기본 트랜잭션 매니저는 daily-record 커넥션의 것이라 TeslaMate SQL에 효력이 없다.
- 시간대는 `Asia/Seoul`. TeslaMate는 UTC 값을 타임존 없는 `timestamp`에 넣는다. **월 경계는 KST로 옮긴 뒤 자른다** — UTC로 자르면 월초 9시간이 옆 달로 샌다.
- 월 경계 기준 컬럼은 **`end_date`**다(`start_date`가 아니다). 측정 시점은 충전이 끝난 때이고, `start_date`로 자르면 자정을 넘긴 오버나이트 충전이 앞 달로 들어간다.
- **nullable 정수·불리언은 `getObject`로 읽는다.** `rs.getInt`는 SQL NULL에 0을 돌려준다. `COUNT(*)`처럼 NULL이 될 수 없는 값만 `getInt`를 쓴다.
- 소수는 `BigDecimal`. 반올림(소수 한 자리)은 **SQL의 `ROUND(...::numeric, 1)`이 한다** — 부동소수 잡음이 응답에 그대로 나가지 않게.
- 테스트는 kotest `BehaviorSpec` + mockk. 격리 모드가 `InstancePerLeaf`라 각 `Given`은 자기 스텁을 스스로 준비한다.
- **컨트롤러 단위 테스트를 쓰지 않는다.** 이 저장소에 `*ControllerTest.kt`가 하나도 없다.
- 차량이 1대라 `car_id`를 파라미터로도 응답으로도 두지 않는다.
- **신차 기준값(568km / 78.5kWh)·잔존율·열화율은 서버에 두지 않는다.** 앱 상수다.

## 설계에서 그대로 옮기는 상수

| 값 | 자리 |
|---|---|
| `end_battery_level >= 80` | 만충 환산 주행거리 표본 조건 |
| `end_battery_level - start_battery_level >= 40` | 사용 가능 용량 표본 조건 |
| `charge_energy_added > 0` | 용량 표본 추가 조건 |
| `end_date IS NOT NULL` | 공통 — 진행 중인 충전 제외 |
| `percentile_cont(0.5)` | 평균이 아니라 중앙값 |
| 소수 1자리 | `ROUND(..., 1)` |

---

### Task 1: 행 타입과 리포지토리 — 월별 집계 SQL

서비스 테스트가 가짜 리포지토리를 세우려면 행 타입과 인터페이스가 먼저 있어야 한다. SQL 구현도 같은 타입을 채우므로 한 태스크에 둔다. **이 태스크에는 새 단위 테스트가 없다** — 이 저장소는 SQL을 단위 테스트로 검증하지 않는다(Task 4에서 실 DB로 확인한다).

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleRows.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleRepository.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/JdbcTeslaVehicleRepository.kt`

**Interfaces:**
- Consumes: 없음
- Produces:
  - `data class BatteryHealthMonthRow(month: YearMonth, fullRangeKm: BigDecimal, capacityKwh: BigDecimal?, sampleCount: Int, capacitySampleCount: Int)`
  - `TeslaVehicleRepository.batteryHealthMonthly(): List<BatteryHealthMonthRow>` — 파라미터가 없다.

- [ ] **Step 1: 행 타입을 더한다**

`TeslaVehicleRows.kt` 맨 끝(`GeofenceRow` 아래)에 붙인다. 파일 상단은 이미 `java.math.BigDecimal`·`java.time.YearMonth`를 import 하고 있으므로 import 추가는 없다.

```kotlin
/**
 * 한 달치 배터리 열화 표본. **두 지표의 표본 조건이 달라 개수도 따로 낸다** —
 * 한 숫자로 합치면 `capacityKwh`가 null인 이유가 「표본이 없어서」인지 「값이 없어서」인지
 * 화면에서 갈리지 않는다.
 *
 * `fullRangeKm`은 non-null이다. 그 달에 행이 왔다는 것은 `end_battery_level >= 80`이고
 * `end_rated_range_km IS NOT NULL`인 충전이 최소 하나 있었다는 뜻이라, 중앙값이 null이 될 길이
 * WHERE에서 막혀 있다.
 */
data class BatteryHealthMonthRow(
    val month: YearMonth,
    val fullRangeKm: BigDecimal,
    /** 그 달에 ΔSoC ≥ 40인 충전이 없으면 null이다. `percentile_cont`가 null 입력을 무시한 결과다. */
    val capacityKwh: BigDecimal?,
    val sampleCount: Int,
    val capacitySampleCount: Int,
)
```

- [ ] **Step 2: 리포지토리 인터페이스에 메서드를 더한다**

`TeslaVehicleRepository.kt`의 `findGeofences()` 아래에 붙인다.

```kotlin
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
```

- [ ] **Step 3: JDBC 구현을 더한다**

`JdbcTeslaVehicleRepository.kt`의 `findGeofences()` 아래, `private fun ResultSet.toPositionRow()` 위에 붙인다.

```kotlin
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
```

`row_count`·`capacity_row_count`는 `COUNT(...)`라 SQL NULL이 될 수 없으므로 `getInt`가 맞다. `capacity_kwh`는 null이 될 수 있는데 `getBigDecimal`은 NULL에 null을 돌려주므로 `getObject` 규칙에 걸리지 않는다(그 규칙은 `getInt`·`getBoolean` 얘기다).

- [ ] **Step 4: SQL 상수를 더한다**

같은 파일 `companion object` 안, `DRIVE_MONTHLY_SQL` 아래에 붙인다.

```kotlin
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
                   AND cp.start_battery_level IS NOT NULL
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
```

- [ ] **Step 5: 컴파일과 기존 테스트가 초록인지 확인한다**

Run: `./gradlew spotlessApply :daily-record:test`
Expected: BUILD SUCCESSFUL. 이 태스크는 새 테스트를 더하지 않으므로 기존 테스트가 그대로 통과해야 한다. `TeslaVehicleRepository`를 구현한 다른 클래스가 있다면 여기서 컴파일이 깨진다 — 그때는 그 구현에도 메서드를 더한다.

- [ ] **Step 6: 커밋**

```bash
git add apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleRows.kt \
        apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleRepository.kt \
        apps/daily-record/src/main/kotlin/com/toy/backend/tesla/JdbcTeslaVehicleRepository.kt
git commit -m "feat: 월별 배터리 열화 표본을 charging_processes에서 집계한다"
```

---

### Task 2: 응답 DTO와 서비스 — 행을 표본으로 옮긴다

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleDtos.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleService.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/tesla/TeslaVehicleServiceTest.kt`

**Interfaces:**
- Consumes: `TeslaVehicleRepository.batteryHealthMonthly(): List<BatteryHealthMonthRow>` (Task 1)
- Produces:
  - `data class TeslaBatteryHealthResponse(samples: List<BatteryHealthSample>)`
  - `data class BatteryHealthSample(yearMonth: YearMonth, fullRangeKm: BigDecimal, capacityKwh: BigDecimal?, sampleCount: Int, capacitySampleCount: Int)`
  - `TeslaVehicleService.batteryHealth(): TeslaBatteryHealthResponse`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`TeslaVehicleServiceTest.kt`의 마지막 `Given` 블록 뒤, 닫는 `})` 앞에 붙인다. 파일 상단은 이미 `BigDecimal`·`YearMonth`를 import 하고 있다.

```kotlin
        // 서비스가 하는 일은 행→표본 변환과 정렬뿐이다. 중앙값·월 경계·표본 조건은 전부 SQL이 한다.
        Given("배터리 건강 표본이 하나도 없을 때") {
            every { vehicleRepository.batteryHealthMonthly() } returns emptyList()

            val response = service.batteryHealth()

            // 빈 배열이어야 한다. null이면 앱이 「응답이 깨졌다」와 「표본이 없다」를 못 가른다.
            Then("samples가 빈 배열이다") {
                response.samples shouldBe emptyList()
            }
        }

        Given("용량 표본이 없는 달이 섞여 있을 때") {
            every { vehicleRepository.batteryHealthMonthly() } returns
                listOf(
                    BatteryHealthMonthRow(YearMonth.of(2026, 7), BigDecimal("527.1"), null, 1, 0),
                    BatteryHealthMonthRow(YearMonth.of(2026, 8), BigDecimal("525.3"), BigDecimal("71.6"), 3, 1),
                )

            val response = service.batteryHealth()

            // ΔSoC ≥ 40인 충전은 몇 달에 한 번이라 capacityKwh는 자주 null이다. 0이 아니다.
            Then("용량 표본이 없는 달은 capacityKwh가 null이고 개수가 0이다") {
                val july = response.samples.first { it.yearMonth == YearMonth.of(2026, 7) }
                july.fullRangeKm shouldBe BigDecimal("527.1")
                july.capacityKwh shouldBe null
                july.sampleCount shouldBe 1
                july.capacitySampleCount shouldBe 0
            }

            Then("용량 표본이 있는 달은 두 개수가 따로 온다") {
                val august = response.samples.first { it.yearMonth == YearMonth.of(2026, 8) }
                august.capacityKwh shouldBe BigDecimal("71.6")
                august.sampleCount shouldBe 3
                august.capacitySampleCount shouldBe 1
            }
        }

        // SQL이 이미 ORDER BY month_start로 내지만, 정렬 책임을 응답 쪽에도 못 박는다 —
        // 앱이 선을 그리는 순서가 여기에 달렸다.
        Given("여러 달의 표본이 뒤섞여 올 때") {
            every { vehicleRepository.batteryHealthMonthly() } returns
                listOf(
                    BatteryHealthMonthRow(YearMonth.of(2026, 8), BigDecimal("525.3"), null, 3, 0),
                    BatteryHealthMonthRow(YearMonth.of(2025, 12), BigDecimal("534.0"), null, 2, 0),
                    BatteryHealthMonthRow(YearMonth.of(2026, 7), BigDecimal("527.1"), null, 1, 0),
                )

            val response = service.batteryHealth()

            Then("오래된 것부터 나온다") {
                response.samples.map { it.yearMonth } shouldBe
                    listOf(YearMonth.of(2025, 12), YearMonth.of(2026, 7), YearMonth.of(2026, 8))
            }

            // 표본이 없는 달(2026-01~06)은 배열에서 빠진다. trend가 빈 달을 채우는 것과 다르다 —
            // 열화는 월 경계가 의미를 갖는 값이 아니라, 선을 이을지 끊을지는 앱이 정한다.
            Then("표본이 없는 달은 자리를 채우지 않는다") {
                response.samples.size shouldBe 3
            }
        }
```

- [ ] **Step 2: 실패하는지 돌린다**

Run: `./gradlew :daily-record:test --tests '*TeslaVehicleServiceTest*'`
Expected: 컴파일 실패 — `Unresolved reference: batteryHealth` / `BatteryHealthMonthRow`가 있으면 `service.batteryHealth()`가 없다는 오류. 아직 서비스에 메서드가 없으므로 이 실패가 맞다.

- [ ] **Step 3: 응답 DTO를 더한다**

`TeslaVehicleDtos.kt` 맨 끝(`TpmsBar` 아래)에 붙인다. 파일 상단은 이미 `BigDecimal`·`YearMonth`를 import 하고 있다.

```kotlin
/**
 * 월별 배터리 열화 표본. **파라미터가 없고 전 기간을 낸다** — 몇 년을 타도 월 행 수는 수십이라
 * 자를 이유가 없고, 열화는 시작점부터 봐야 의미가 있다. 몇 개월을 그릴지는 앱이 정한다.
 *
 * **잔존율·열화율을 내지 않는다.** 신차 기준값(차종·연식 상수)은 TeslaMate에 없고, 서버가
 * 잔존율을 내면 그 반올림·경계 처리를 화면이 따라야 한다. km당 비용을 내지 않는 것과 같은 이유다.
 */
data class TeslaBatteryHealthResponse(
    /** 오래된 것부터. **표본이 없는 달은 빠진다** — `trend`가 빈 달의 자리를 채우는 것과 다르다. */
    val samples: List<BatteryHealthSample>,
)

/**
 * `end_rated_range_km ÷ end_battery_level × 100`(만충 환산)과
 * `charge_energy_added ÷ ΔSoC × 100`(사용 가능 용량)의 **그 달 중앙값**이다.
 *
 * 표본 조건이 서로 달라 개수를 따로 낸다 — 한 숫자로 합치면 `capacityKwh`가 null인 이유가
 * 「표본이 없어서」인지 「값이 없어서」인지 화면에서 갈리지 않는다.
 */
data class BatteryHealthSample(
    val yearMonth: YearMonth,
    /** 만충 환산 주행거리(km). `end_battery_level >= 80`인 충전만 표본이다. */
    val fullRangeKm: BigDecimal,
    /** 사용 가능 용량(kWh). ΔSoC ≥ 40인 충전이 그 달에 없으면 **null이다. 0이 아니다.** */
    val capacityKwh: BigDecimal?,
    val sampleCount: Int,
    val capacitySampleCount: Int,
)
```

- [ ] **Step 4: 서비스 메서드를 더한다**

`TeslaVehicleService.kt`의 `status()` 아래, `private fun resolveState(...)` 위에 붙인다.

```kotlin
    /**
     * 쿼리 한 번이 전부다. **중앙값·월 경계(KST)·표본 조건은 SQL이 하고**, 여기서는 행을
     * 표본으로 옮기고 오래된 것부터로 못 박기만 한다.
     *
     * 표본이 없는 달의 자리를 채우지 않는다 — `summary`의 `trend`와 반대다. 그쪽은
     * 「그 달에 안 탔다」와 「기록이 없다」를 구분해야 하지만, 열화는 월 경계가 의미를 갖는
     * 값이 아니다. 선을 이을지 끊을지는 앱이 정한다.
     */
    fun batteryHealth(): TeslaBatteryHealthResponse =
        TeslaBatteryHealthResponse(
            samples =
                vehicleRepository
                    .batteryHealthMonthly()
                    .sortedBy { it.month }
                    .map {
                        BatteryHealthSample(
                            yearMonth = it.month,
                            fullRangeKm = it.fullRangeKm,
                            capacityKwh = it.capacityKwh,
                            sampleCount = it.sampleCount,
                            capacitySampleCount = it.capacitySampleCount,
                        )
                    },
        )
```

- [ ] **Step 5: 통과하는지 돌린다**

Run: `./gradlew spotlessApply :daily-record:test --tests '*TeslaVehicleServiceTest*'`
Expected: PASS. 새로 더한 다섯 개의 `Then`이 전부 초록이어야 한다.

- [ ] **Step 6: 커밋**

```bash
git add apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleDtos.kt \
        apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleService.kt \
        apps/daily-record/src/test/kotlin/com/toy/backend/tesla/TeslaVehicleServiceTest.kt
git commit -m "feat: 배터리 열화 표본을 월별 응답으로 옮긴다"
```

---

### Task 3: 엔드포인트 배선 — `GET /tesla/battery-health`

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleController.kt`

**Interfaces:**
- Consumes: `TeslaVehicleService.batteryHealth(): TeslaBatteryHealthResponse` (Task 2)
- Produces: `GET /tesla/battery-health` → 200 `DataResponseBody<TeslaBatteryHealthResponse>`

- [ ] **Step 1: 컨트롤러 KDoc의 엔드포인트 목록을 갱신한다**

지금 클래스 KDoc은 차량 쪽을 `/tesla/summary`·`/tesla/status` 둘로 적고 있다. 셋이 된다.

기존:

```kotlin
 * 충전(`/tesla/charges` 하위)과 차량(`/tesla/summary`·`/tesla/status`)을 갈라 둔다 —
```

바꾼 뒤:

```kotlin
 * 충전(`/tesla/charges` 하위)과 차량(`/tesla/summary`·`/tesla/status`·`/tesla/battery-health`)을
 * 갈라 둔다 —
```

- [ ] **Step 2: 매핑을 더한다**

`status()` 아래에 붙인다.

```kotlin
    /**
     * 읽는 테이블은 `charging_processes`지만 **충전이 아니라 차량에 붙인다** —
     * 이 값이 답하는 질문은 「차가 어떤 상태인가」다.
     *
     * 파라미터가 없다. 전 기간을 내고, 몇 개월을 그릴지는 앱이 정한다.
     */
    @GetMapping("/battery-health")
    @Operation(summary = "월별 배터리 열화 표본 — 만충 환산 주행거리·사용 가능 용량의 중앙값과 표본 수")
    fun batteryHealth(): ResponseEntity<DataResponseBody<TeslaBatteryHealthResponse>> =
        ResponseEntity.ok(DataResponseBody(service.batteryHealth()))
```

- [ ] **Step 3: 전체 빌드가 초록인지 확인한다**

Run: `./gradlew spotlessApply build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: 새 심볼이 응답까지 실렸는지 대 본다** (AGENTS.md 「커밋 전」 검사)

Run:

```bash
grep -rln 'capacitySampleCount' --include='*.kt' .
```

Expected: 목록에 **`TeslaVehicleDtos.kt`가 반드시 있어야 한다** — 없으면 그 값은 앱까지 가지 않는다. `TeslaVehicleRows.kt`·`TeslaVehicleService.kt`·`TeslaVehicleServiceTest.kt`도 함께 나온다. `fullRangeKm`·`capacityKwh`·`sampleCount`로도 같은 검사를 한 번씩 돌린다.

- [ ] **Step 5: 커밋**

```bash
git add apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleController.kt
git commit -m "feat: 배터리 건강 조회를 GET /tesla/battery-health로 연다"
```

---

### Task 4: 실 DB에서 SQL을 눈으로 확인한다

단위 테스트는 리포지토리를 목으로 대체하므로 **SQL 자체는 검증되지 않는다.** 설계 문서가 확인하라고 적은 세 가지를 실제 TeslaMate DB에서 한 번 돌린다. 접속 정보는 사용자에게 받는다.

**Files:** 없음 (읽기만 한다)

**Interfaces:**
- Consumes: Task 1의 `BATTERY_HEALTH_MONTHLY_SQL`
- Produces: 없음 — 확인 결과를 Task 5의 changelog에 적는다.

- [ ] **Step 1: 집계 SQL을 그대로 돌린다**

`BATTERY_HEALTH_MONTHLY_SQL`을 psql에 붙여 넣어 돌린다.

Expected: 월별로 한 행씩, `full_range_km`이 소수 한 자리. 2026-08 행의 `full_range_km`이 **525 안팎**이고 `capacity_kwh`가 **71.6 안팎**이어야 한다 — 설계 문서에 적힌 2026-08-15 충전(17%→99%, 90km→520km, 58.7kWh)의 실측과 맞는지 본다.

- [ ] **Step 2: KST 월 경계가 실제로 잘렸는지 본다**

```sql
SELECT cp.id,
       cp.end_date                                                  AS end_utc,
       cp.end_date AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Seoul'     AS end_kst,
       date_trunc('month', cp.end_date)::date                       AS month_utc,
       date_trunc('month', cp.end_date AT TIME ZONE 'UTC'
                                       AT TIME ZONE 'Asia/Seoul')::date AS month_kst
  FROM charging_processes cp
 WHERE cp.end_date IS NOT NULL
   AND EXTRACT(day FROM cp.end_date AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Seoul') = 1
 ORDER BY cp.end_date DESC
 LIMIT 10
```

Expected: KST로 1일 새벽(00:00~08:59)에 끝난 충전이 있으면 `month_utc`와 `month_kst`가 **한 달 어긋나야 한다**. 어긋나는 행이 우리가 KST를 쓰는 이유다. 해당 행이 없으면 「이 데이터에는 경계 사례가 없다」로 기록하고 넘어간다(SQL이 틀렸다는 뜻은 아니다).

- [ ] **Step 3: `percentile_cont`가 null을 건너뛰는지 본다**

```sql
SELECT COUNT(*)                                                        AS row_count,
       COUNT(v)                                                        AS non_null_count,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY v::double precision) AS median
  FROM (VALUES (10.0), (NULL), (20.0)) AS t(v)
```

Expected: `row_count = 3`, `non_null_count = 2`, `median = 15`. null이 무시되고 개수만 따로 세진다는 것을 확인하는 것이다 — 이 성질 위에 `capacityKwh`가 null이 되는 규칙이 서 있다.

- [ ] **Step 4: 용량 표본이 실제로 몇 건인지 센다**

```sql
SELECT COUNT(*) FILTER (WHERE cp.end_battery_level >= 80)                                    AS range_samples,
       COUNT(*) FILTER (WHERE cp.end_battery_level - cp.start_battery_level >= 40
                          AND cp.charge_energy_added > 0)                                     AS capacity_samples
  FROM charging_processes cp
 WHERE cp.end_date IS NOT NULL
   AND cp.end_rated_range_km IS NOT NULL
   AND cp.start_battery_level IS NOT NULL
```

Expected: `capacity_samples`가 `range_samples`보다 훨씬 적다. 설계가 「`capacityKwh`는 자주 null이다」라고 적은 근거이고, 실제 숫자를 changelog에 적는다.

- [ ] **Step 5: 앱을 띄워 엔드포인트를 부른다**

`daily-record`를 띄우고 인증 토큰으로 호출한다.

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/tesla/battery-health | jq .
```

Expected: `data.samples`가 오래된 달부터 오는 배열이고, `yearMonth`가 `"2026-08"` 꼴이며, 용량 표본이 없는 달의 `capacityKwh`가 `null`(0이 아니다)이다. Step 1의 psql 결과와 값이 같아야 한다.

- [ ] **Step 6: 확인 결과를 기록한다**

커밋할 코드는 없다. 위 다섯 단계의 실제 숫자(월 행 수, 최근 달의 `fullRangeKm`·`capacityKwh`, 표본 건수, 경계 사례 유무)를 적어 Task 5로 넘긴다.

---

### Task 5: changelog

**Files:**
- Create: `docs/changelog/2026-08-17-tesla-battery-health.md`

**Interfaces:**
- Consumes: Task 4의 실측 숫자
- Produces: 없음

- [ ] **Step 1: 기존 changelog의 형식을 본다**

Run: `cat docs/changelog/2026-08-13-tesla-vehicle-summary.md`
같은 절 구성과 문체를 따른다.

- [ ] **Step 2: 항목을 쓴다**

담아야 하는 것:

- `GET /tesla/battery-health`가 `charging_processes`에서 월별 만충 환산 주행거리·사용 가능 용량의 **중앙값**을 표본 수와 함께 낸다.
- 표본 조건 두 개(`end_battery_level >= 80`, ΔSoC ≥ 40)와 **왜 개수를 따로 내는지**.
- **평균이 아니라 중앙값인 이유**(급속 충전 직후 `rated_battery_range_km`가 튄다).
- **나눗셈을 서버가 한 예외**와 그 조건(중앙값은 표본 집합 위의 연산이라 나누기 전에는 낼 수 없다 / 분모가 0이 될 길을 WHERE에서 막았다).
- 월 경계를 **`end_date` 기준 KST**로 자른 이유.
- **신차 기준값·잔존율을 서버에 두지 않았다**는 것 — 앱 상수다.
- Task 4에서 실측한 숫자.
- 이번에 하지 않은 것: 2단계 `/tesla/drive-insights`, 3단계 누적 합계·충전 곡선, 보류인 `positions`.

- [ ] **Step 3: 커밋**

```bash
git add docs/changelog/2026-08-17-tesla-battery-health.md
git commit -m "docs: 배터리 건강 집계를 changelog에 적는다"
```

---

## 이번 계획에서 하지 않는 것

- **2단계 `GET /tesla/drive-insights`** — 설계에 `cars.efficiency`가 실제로 무엇을 담는지 확인이 열린 항목으로 남아 있다. 그 단위를 확정하기 전에는 온도별 전비의 kWh 환산이 틀릴 수 있다.
- **3단계 누적 합계·충전 곡선.**
- **보류인 `positions`** — `positions.drive_id` 인덱스 확인이 선행 조건이다.
- **급속 충전 직후 표본 배제**(`charges.fast_charger_present`) — 중앙값으로 충분한지 먼저 본다.
