# TeslaMate 상태 타임라인·주행 통계 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `GET /tesla/state-timeline`을 새로 두고, `GET /tesla/drive-insights`에 역대 최고 속도와 이번 달·올해 주행거리 세 필드를 더한다.

**Architecture:** 기존 `com.toy.backend.tesla` 다섯 파일(Rows → Repository 인터페이스 → Jdbc 구현 → Dtos → Service → Controller)에 얹는다. 새 파일을 만들지 않는다 — 타임라인은 `TeslaVehicleService`가 이미 읽는 `states`·`drives`·`charging_processes`를 그대로 읽고, 주행 통계는 기존 `driveInsights` 응답에 필드로 붙는다. 집계·창 자르기·KST 변환 경계는 기존 규약을 그대로 따른다: **SQL이 자르고 합치고, 서비스는 KST로 되돌리고 DTO로 옮기기만 한다.**

**Tech Stack:** Kotlin, Spring Boot, `JdbcClient`(TeslaMate 보조 DataSource), kotest `BehaviorSpec` + mockk

**Spec:** `docs/superpowers/specs/2026-08-19-tesla-state-timeline-drive-stats-design.md`

> **개정 (2026-08-19).** 아래 태스크는 **초판 계약**을 구현한 것이고 `0e2b8a9`(#44)로 머지됐다.
> 그 뒤 스펙이 개정되어(`154595a`) 두 곳이 바뀌었으므로, **이 문서를 그대로 실행하면 옛 계약이 나온다.**
> 현재 코드는 개정 계약을 따른다 — 무엇이 다른지는 스펙의 개정 블록을 보라.
>
> | 자리 | 초판 (이 문서) | 개정 (현재 코드) |
> |---|---|---|
> | 타임라인 파라미터 | `days` 1~30, 기본 7 | `hours` 1~168, 기본 24 |
> | 범위 시작 | KST 자정 − (days−1)일 | `to` − `hours`시간 (자정 스냅 없음) |
> | 응답 필드 | `days` | `hours` |
> | 주행 통계 | `monthDistanceKm`·`yearDistanceKm` (월·연 경계) | `totalDistanceKm`·`recordedMonths` (전 기간, 평균의 분자·분모) |

## Global Constraints

- **TeslaMate 시각은 타임존 없는 `timestamp`에 든 UTC 값이다.** SQL에서 `now()`(timestamptz)와 직접 비교하지 않고 `(now() AT TIME ZONE 'UTC')`로 맞춘다. 행 타입(`*Row`)의 시각은 전부 UTC이고, KST로 되돌리는 것은 서비스가 `TeslaTime.toKst`로 한다.
- **`@Transactional`을 붙이지 않는다.** 기본 트랜잭션 매니저는 daily-record 커넥션의 것이라 TeslaMate SQL에 효력이 없다.
- **nullable 정수는 `rs.getObject`로 읽는다.** `rs.getInt`는 SQL NULL에 0을 준다.
- **커밋 전 `./gradlew spotlessApply` 필수.**
- 커밋 메시지는 기존 관례를 따른다(한국어, `feat:`/`docs:`, 현재형 종결).
- `car_id`를 파라미터로도 응답으로도 두지 않는다. 차량이 1대다.
- 컨트롤러 단위 테스트는 이 저장소 관례대로 쓰지 않는다.

---

## File Structure

| 파일 | 이 계획에서의 책임 |
|---|---|
| `TeslaTime.kt` | 타임라인 창(KST 자정 기준) 계산을 더한다. 순수 함수로 두어 테스트가 시각을 못 박을 수 있게 한다 |
| `TeslaVehicleRows.kt` | `StateSegmentRow`·`SegmentRow`·`DriveStatsRow` 세 행 타입 |
| `TeslaVehicleRepository.kt` | `stateSegments`·`driveSegments`·`chargeSegments`·`driveStats` 네 메서드 |
| `JdbcTeslaVehicleRepository.kt` | 위 넷의 SQL과 매핑 |
| `TeslaVehicleDtos.kt` | `TeslaStateTimelineResponse`·`StateSegment`·`TimeSegment`, `TeslaDriveInsightsResponse` 필드 셋 |
| `TeslaVehicleController.kt` | `GET /tesla/state-timeline` |
| `TeslaVehicleService.kt` | `stateTimeline(days)` 신설, `driveInsights`에 통계 세 필드 |
| `TeslaVehicleServiceTest.kt` | 위 둘의 테스트 |

`charging_processes`를 읽지만 **`TeslaChargeRepository`가 아니라 `TeslaVehicleRepository`에 둔다** — `batteryHealthMonthly`가 이미 같은 판단을 했다(읽는 테이블이 아니라 답하는 질문이 「차가 어떤 상태인가」다).

---

### Task 1: 타임라인 창 계산 (`TeslaTime`)

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaTime.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/tesla/TeslaTimeTest.kt` (신규)

**Interfaces:**
- Consumes: 없음
- Produces:
  - `TeslaTime.nowKst(): LocalDateTime`
  - `TeslaTime.timelineWindowKst(days: Int, nowKst: LocalDateTime): Pair<LocalDateTime, LocalDateTime>` — `first`가 창 시작(KST 자정), `second`가 창 끝(= `nowKst` 그대로)

**왜 순수 함수인가:** 이 저장소에는 `Clock` 주입 관례가 없고 `LocalDate.now()`를 직접 쓴다. 그 관례를 깨지 않으면서 「KST 자정 − (days−1)일」이라는 계산만은 고정 시각으로 검증하려면, 시각을 인자로 받는 순수 함수와 `now()`를 읽는 얇은 함수를 가르는 것이 가장 싸다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`TeslaTimeTest.kt` 신규:

```kotlin
package com.toy.backend.tesla

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime

/**
 * 창 계산만 본다 — `toUtc`·`toKst`는 기존 코드가 이미 쓰고 있고 여기서 바꾸지 않는다.
 *
 * **시각을 인자로 받는 순수 함수라 못 박을 수 있다.** `nowKst()`를 직접 부르는 함수였다면
 * 자정 근처에서만 깨지는 테스트가 됐을 것이다.
 */
class TeslaTimeTest :
    BehaviorSpec({
        Given("days=7로 창을 계산할 때") {
            val now = LocalDateTime.of(2026, 8, 19, 12, 34, 56)
            val (from, to) = TeslaTime.timelineWindowKst(7, now)

            // 앱이 하루에 한 행씩 그린다. 창이 임의 시각에서 시작하면 첫 행이 반쪽이 된다.
            Then("시작은 KST 오늘 자정에서 6일을 뺀 자정이다") {
                from shouldBe LocalDateTime.of(2026, 8, 13, 0, 0)
            }

            Then("끝은 요청 시각 그대로다") {
                to shouldBe now
            }
        }

        // days=1이면 오늘 하루만 본다 — 자정에서 0일을 뺀다.
        Given("days=1로 창을 계산할 때") {
            val now = LocalDateTime.of(2026, 8, 19, 12, 34, 56)

            Then("시작이 오늘 자정이다") {
                TeslaTime.timelineWindowKst(1, now).first shouldBe LocalDateTime.of(2026, 8, 19, 0, 0)
            }
        }

        // 달을 거스르는 경계다. LocalDate 산술이 알아서 하지만 못 박아 둔다.
        Given("월초에 days=30으로 창을 계산할 때") {
            val now = LocalDateTime.of(2026, 3, 2, 9, 0)

            Then("시작이 전달로 넘어간다") {
                TeslaTime.timelineWindowKst(30, now).first shouldBe LocalDateTime.of(2026, 2, 1, 0, 0)
            }
        }
    })
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :daily-record:test --tests 'com.toy.backend.tesla.TeslaTimeTest'`
Expected: 컴파일 실패 — `Unresolved reference: timelineWindowKst`

- [ ] **Step 3: 최소 구현**

`TeslaTime.kt`에 `LocalDateTime.now(KST)`를 쓰므로 import는 이미 있는 것으로 충분하다. `monthRangeUtc` 아래에 더한다:

```kotlin
    /** 지금(KST). 창 계산을 순수 함수로 두려고 시각 읽기만 여기로 뺀다. */
    fun nowKst(): LocalDateTime = LocalDateTime.now(KST)

    /**
     * 최근 `days`일의 타임라인 창(KST). `first`가 시작, `second`가 끝이다.
     *
     * **시작을 KST 자정에 맞춘다.** 앱이 하루에 한 행씩 그리므로, 창이 임의 시각에서
     * 시작하면 첫 행과 마지막 행이 둘 다 잘린 반쪽이 된다. `days=7`이면 온전한 6일 +
     * 오늘 부분 = 7행이다.
     *
     * 끝은 요청 시각 그대로다 — 진행 중인 상태·주행·충전을 여기서 막는다.
     */
    fun timelineWindowKst(
        days: Int,
        nowKst: LocalDateTime = nowKst(),
    ): Pair<LocalDateTime, LocalDateTime> =
        nowKst.toLocalDate().minusDays((days - 1).toLong()).atStartOfDay() to nowKst
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :daily-record:test --tests 'com.toy.backend.tesla.TeslaTimeTest'`
Expected: PASS (3 tests)

- [ ] **Step 5: 커밋**

```bash
./gradlew spotlessApply
git add apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaTime.kt \
        apps/daily-record/src/test/kotlin/com/toy/backend/tesla/TeslaTimeTest.kt
git commit -m "feat: 타임라인 창을 KST 자정에 맞춰 계산한다"
```

---

### Task 2: 행 타입과 리포지토리 계약

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleRows.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleRepository.kt`

**Interfaces:**
- Consumes: Task 1의 창(호출자가 UTC로 바꿔 넘긴다)
- Produces:
  - `StateSegmentRow(state: String, fromUtc: LocalDateTime, toUtc: LocalDateTime)`
  - `SegmentRow(fromUtc: LocalDateTime, toUtc: LocalDateTime)`
  - `DriveStatsRow(maxSpeedKmh: Int?, monthDistanceKm: BigDecimal, yearDistanceKm: BigDecimal)`
  - `TeslaVehicleRepository.stateSegments(windowStartUtc, windowEndUtc): List<StateSegmentRow>`
  - `TeslaVehicleRepository.driveSegments(windowStartUtc, windowEndUtc): List<SegmentRow>`
  - `TeslaVehicleRepository.chargeSegments(windowStartUtc, windowEndUtc): List<SegmentRow>`
  - `TeslaVehicleRepository.driveStats(): DriveStatsRow`

이 태스크에는 테스트 사이클이 없다 — 인터페이스와 데이터 클래스뿐이라 컴파일이 검사의 전부다. Task 3이 구현을, Task 6이 동작을 검사한다.

- [ ] **Step 1: 행 타입 셋을 더한다**

`TeslaVehicleRows.kt` 끝(`DrivePlaceRow` 아래)에:

```kotlin
/*
 * 아래 셋은 `/tesla/state-timeline`과 주행 통계의 행 타입이다.
 */

/**
 * `states`의 한 구간. **창에 맞춰 이미 잘려서 온다** — 자르는 규칙을 서버 한 곳에만 두려는
 * 것이다(앱이 창 밖 값을 받아 스스로 자르면 규칙이 두 곳에 생긴다).
 *
 * `state`는 `online`·`offline`·`asleep`이다. `StateRow`와 같은 이유로 **번역하지 않는다**.
 */
data class StateSegmentRow(
    val state: String,
    val fromUtc: LocalDateTime,
    val toUtc: LocalDateTime,
)

/**
 * 주행·충전의 한 구간. 둘이 같은 모양이라 타입을 함께 쓴다 — 응답에서도 배열만 다르다.
 *
 * **마감되지 않은 유령 세션은 여기 오지 않는다.** 리포지토리 SQL의 24시간 창이 거른다
 * (`ActivityRow`와 같은 규칙이다). 진행 중인 진짜 세션은 `toUtc`가 창 끝으로 막혀 온다.
 */
data class SegmentRow(
    val fromUtc: LocalDateTime,
    val toUtc: LocalDateTime,
)

/**
 * 역대 최고 속도와 이번 달·올해 주행거리. **셋의 창이 서로 다르다** —
 * 최고 속도는 전 기간이고 거리 둘은 KST 월·연 경계다.
 *
 * `maxSpeedKmh`만 nullable이다. 주행이 하나도 없으면 「역대 최고」라는 값 자체가 없지만,
 * 거리 둘은 기간이 못박힌 합계라 그 기간에 안 탔으면 **0이 사실이다**.
 */
data class DriveStatsRow(
    val maxSpeedKmh: Int?,
    val monthDistanceKm: BigDecimal,
    val yearDistanceKm: BigDecimal,
)
```

- [ ] **Step 2: 리포지토리 메서드 넷을 더한다**

`TeslaVehicleRepository.kt`의 `carEfficiency()` 위에:

```kotlin
    /**
     * 창에 걸치는 `states` 구간. **창 경계로 잘라서 준다.**
     *
     * 열린 행(`end_date IS NULL`)에 최근성 조건을 걸지 않는다 —
     * 유니크 인덱스(`states_car_id__end_date_IS_NULL_index`)가 차당 하나를 보장하므로
     * 그것은 유령이 아니라 **현재 상태**다. `drives`·`charging_processes`와 다른 점이다.
     */
    fun stateSegments(
        windowStartUtc: LocalDateTime,
        windowEndUtc: LocalDateTime,
    ): List<SegmentRow>

    /**
     * 창에 걸치는 주행 구간. **마감되지 않은 유령을 여기서 막는다.**
     *
     * 열린 행은 `start_date >= now − 24h`인 것만 「진행 중」으로 인정하고 나머지는 버린다.
     * 이 DB에는 2022~2024년에 시작된 열린 주행이 12건 있다 — 조건이 없으면 그중 하나가
     * **창 전체를 주행으로 칠한다.**
     */
    fun driveSegments(
        windowStartUtc: LocalDateTime,
        windowEndUtc: LocalDateTime,
    ): List<SegmentRow>

    /** 창에 걸치는 충전 구간. 유령 규칙은 `driveSegments`와 같다(열린 행 6건, 2021~2025년). */
    fun chargeSegments(
        windowStartUtc: LocalDateTime,
        windowEndUtc: LocalDateTime,
    ): List<SegmentRow>

    /**
     * 역대 최고 속도와 이번 달·올해 주행거리. **`months` 창을 쓰지 않는다** —
     * 창이 바뀔 때마다 바뀌면 기록이 아니고, 월·연은 그 자체가 경계다.
     *
     * `GROUP BY`가 없어 `drives`가 비어도 한 행이 온다.
     */
    fun driveStats(): DriveStatsRow
```

> `stateSegments`의 반환 타입은 `List<StateSegmentRow>`다. 위 주석 블록의 시그니처를 옮길 때 이 한 줄을 확인하라.

- [ ] **Step 3: 컴파일을 확인한다**

Run: `./gradlew :daily-record:compileKotlin`
Expected: FAIL — `JdbcTeslaVehicleRepository`가 새 멤버 넷을 구현하지 않았다는 오류. Task 3이 이것을 없앤다.

---

### Task 3: SQL과 매핑 (`JdbcTeslaVehicleRepository`)

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/JdbcTeslaVehicleRepository.kt`

**Interfaces:**
- Consumes: Task 2의 인터페이스와 행 타입
- Produces: 네 메서드의 구현. 외부에 새로 드러나는 이름은 없다.

**리포지토리 통합 테스트를 만들지 않는다.** 이 저장소에 TeslaMate DB를 띄우는 테스트가 없다. 대신 스펙 문서에 실측값을 남겼다(이번 달 1,331.3 / 올해 13,440.4 / 최고 138).

- [ ] **Step 1: 매핑 넷을 더한다**

`carEfficiency()` 아래, `toPositionRow()` 위에:

```kotlin
    override fun stateSegments(
        windowStartUtc: LocalDateTime,
        windowEndUtc: LocalDateTime,
    ): List<StateSegmentRow> =
        teslaMateJdbcClient
            .sql(STATE_SEGMENTS_SQL)
            .param("windowStart", windowStartUtc)
            .param("windowEnd", windowEndUtc)
            .query { rs, _ ->
                StateSegmentRow(
                    state = rs.getString("state"),
                    fromUtc = rs.getObject("from_utc", LocalDateTime::class.java),
                    toUtc = rs.getObject("to_utc", LocalDateTime::class.java),
                )
            }.list()

    override fun driveSegments(
        windowStartUtc: LocalDateTime,
        windowEndUtc: LocalDateTime,
    ): List<SegmentRow> = segments(DRIVE_SEGMENTS_SQL, windowStartUtc, windowEndUtc)

    override fun chargeSegments(
        windowStartUtc: LocalDateTime,
        windowEndUtc: LocalDateTime,
    ): List<SegmentRow> = segments(CHARGE_SEGMENTS_SQL, windowStartUtc, windowEndUtc)

    override fun driveStats(): DriveStatsRow =
        teslaMateJdbcClient
            .sql(DRIVE_STATS_SQL)
            .query { rs, _ ->
                DriveStatsRow(
                    maxSpeedKmh = rs.nullableInt("max_speed_kmh"),
                    monthDistanceKm = rs.getBigDecimal("month_distance_km"),
                    yearDistanceKm = rs.getBigDecimal("year_distance_km"),
                )
            }.single()

    /** 주행·충전이 같은 모양이라 매핑을 함께 쓴다. 다른 것은 SQL의 테이블 이름뿐이다. */
    private fun segments(
        sql: String,
        windowStartUtc: LocalDateTime,
        windowEndUtc: LocalDateTime,
    ): List<SegmentRow> =
        teslaMateJdbcClient
            .sql(sql)
            .param("windowStart", windowStartUtc)
            .param("windowEnd", windowEndUtc)
            .query { rs, _ ->
                SegmentRow(
                    fromUtc = rs.getObject("from_utc", LocalDateTime::class.java),
                    toUtc = rs.getObject("to_utc", LocalDateTime::class.java),
                )
            }.list()
```

- [ ] **Step 2: SQL을 더한다**

`companion object` 안, `GEOFENCES_SQL` 아래에:

```kotlin
        /**
         * **구간을 창 경계로 자른다.** 앱이 창 밖 값을 받아 스스로 자르게 두지 않는다 —
         * 자르는 규칙이 두 곳에 생긴다.
         *
         * `:windowStart`·`:windowEnd`는 **UTC**로 온다. TeslaMate가 타임존 없는 컬럼에 UTC를
         * 넣으므로 KST로 넘기면 9시간이 어긋난다.
         *
         * 열린 행은 `end_date`가 null이라 `COALESCE`로 창 끝까지 열어 둔 뒤 `LEAST`로 막는다.
         */
        private const val SEGMENT_COLUMNS = """
                   GREATEST(t.start_date, :windowStart)                AS from_utc,
                   LEAST(COALESCE(t.end_date, :windowEnd), :windowEnd) AS to_utc
        """

        /**
         * 창에 **걸치기만 해도** 가져온다 — 안에 온전히 든 것만 뽑으면 창을 가로지르는 긴
         * 구간(오프라인 며칠)이 통째로 빠진다.
         */
        private const val SEGMENT_OVERLAP = """
             WHERE t.start_date < :windowEnd
               AND COALESCE(t.end_date, :windowEnd) > :windowStart
        """

        /**
         * **마감되지 않은 유령을 여기서 막는다.** 열린 행은 최근 24시간에 시작된 것만
         * 「진행 중」으로 인정한다 — `ACTIVITY_SQL`과 같은 규칙이고 같은 이유로 24시간이다
         * (완속 오버나이트가 10시간쯤이고 24시간 연속 주행은 없다. **새 유령도 하루면 낫는다**).
         *
         * 이 조건이 없으면 2022년에 시작된 열린 주행 하나가 `COALESCE(end_date, :windowEnd)`를
         * 타고 **창 전체를 주행으로 칠한다.** `/tesla/status`에서 `8cb61d9`가 고친 것과 같은
         * 결함인데, 타임라인에서는 한 칸이 아니라 띠 전체가 틀린다.
         */
        private const val SEGMENT_NOT_GHOST = """
               AND (t.end_date IS NOT NULL
                    OR t.start_date >= (now() AT TIME ZONE 'UTC') - interval '24 hours')
        """

        /**
         * **`states`에는 유령 규칙을 적용하지 않는다.** 유니크 인덱스
         * (`states_car_id__end_date_IS_NULL_index`)가 열린 행을 차당 하나로 강제하므로
         * 그것은 유령이 아니라 현재 상태다.
         *
         * `state`는 enum이라 `::text`로 내린다. **번역하지 않는다** — 상류가 값을 늘리면
         * 그대로 올라온다.
         */
        private const val STATE_SEGMENTS_SQL = """
            SELECT t.state::text AS state,
                   $SEGMENT_COLUMNS
              FROM states t
            $SEGMENT_OVERLAP
             ORDER BY t.start_date
        """

        private const val DRIVE_SEGMENTS_SQL = """
            SELECT $SEGMENT_COLUMNS
              FROM drives t
            $SEGMENT_OVERLAP
            $SEGMENT_NOT_GHOST
             ORDER BY t.start_date
        """

        private const val CHARGE_SEGMENTS_SQL = """
            SELECT $SEGMENT_COLUMNS
              FROM charging_processes t
            $SEGMENT_OVERLAP
            $SEGMENT_NOT_GHOST
             ORDER BY t.start_date
        """

        /**
         * **`/tesla/summary`의 이번 달 `distanceKm`과 같은 숫자가 나와야 한다.** 두 화면에
         * 다른 숫자가 뜨면 어느 쪽도 못 믿는다 — 그래서 모집단(`end_date IS NOT NULL`,
         * 거리 조건 없음)·경계 컬럼(`start_date`)·시간대(KST)·반올림(소수 한 자리)을
         * `DRIVE_MONTHLY_SQL`과 정확히 맞춘다. 한쪽을 고치면 다른 쪽도 고쳐야 한다.
         *
         * **최고 속도만 창이 없다.** 창이 바뀔 때마다 바뀌면 기록이 아니다. 실측 138 km/h는
         * 2024~2025년 것이라 12개월 창으로 자르면 134가 된다 — 앱이 라벨을 「역대 최고」로
         * 적어 옆 두 타일과 범위가 다름을 글자로 드러낸다.
         *
         * **거리 둘은 `COALESCE(…, 0)`으로 0을 낸다.** 기간이 못박힌 합계라 그 기간에 주행이
         * 없으면 「0km 탔다」가 사실이다. null로 두면 **매달 1일 새벽마다 화면에 「—」가 뜬다.**
         *
         * `GROUP BY`가 없어 `drives`가 비어도 한 행이 온다 — `max_speed_kmh`는 null,
         * 거리 둘은 0이다.
         */
        private const val DRIVE_STATS_SQL = """
            SELECT MAX(d.speed_max) AS max_speed_kmh,
                   ROUND(COALESCE(SUM(d.distance) FILTER (
                       WHERE date_trunc('month', d.start_date AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Seoul')
                           = date_trunc('month', now() AT TIME ZONE 'Asia/Seoul')), 0)::numeric, 1) AS month_distance_km,
                   ROUND(COALESCE(SUM(d.distance) FILTER (
                       WHERE date_trunc('year',  d.start_date AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Seoul')
                           = date_trunc('year',  now() AT TIME ZONE 'Asia/Seoul')), 0)::numeric, 1) AS year_distance_km
              FROM drives d
             WHERE d.end_date IS NOT NULL
        """
```

- [ ] **Step 3: 컴파일을 확인한다**

Run: `./gradlew :daily-record:compileKotlin`
Expected: PASS

- [ ] **Step 4: 커밋**

```bash
./gradlew spotlessApply
git add apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleRows.kt \
        apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleRepository.kt \
        apps/daily-record/src/main/kotlin/com/toy/backend/tesla/JdbcTeslaVehicleRepository.kt
git commit -m "feat: 상태 구간과 주행 통계를 읽는다"
```

---

### Task 4: 응답 타입 (`TeslaVehicleDtos`)

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleDtos.kt`

**Interfaces:**
- Consumes: 없음
- Produces:
  - `TeslaStateTimelineResponse(days: Int, from: LocalDateTime, to: LocalDateTime, states: List<StateSegment>, drives: List<TimeSegment>, charges: List<TimeSegment>)`
  - `StateSegment(state: String, from: LocalDateTime, to: LocalDateTime)`
  - `TimeSegment(from: LocalDateTime, to: LocalDateTime)`
  - `TeslaDriveInsightsResponse`에 `maxSpeedKmh: Int?`, `monthDistanceKm: BigDecimal`, `yearDistanceKm: BigDecimal`

- [ ] **Step 1: 타임라인 응답 타입을 더한다**

`TeslaVehicleDtos.kt` 끝(`DrivePlace` 아래)에:

```kotlin
/**
 * 최근 며칠의 차량 상태를 시간축 구간으로 낸다. **시각은 전부 KST다.**
 *
 * **세 계열을 그대로 낸다 — 하나의 띠로 합치지 않는다.** 합치려면 구간 산술(빼기·쪼개기)이
 * 필요하다: `online` 구간 하나가 주행 둘에 걸치면 셋으로 갈라져야 하고, 그 로직은 SQL로도
 * 코틀린으로도 만만치 않다. 세 계열을 그대로 내면 서버는 단순 조회 셋으로 끝나고, 겹칠 때
 * 무엇이 이기는지는 화면이 정한다(TeslaMate의 Grafana 대시보드가 세 계열을 레이어로 겹쳐
 * 그리는 것과 같은 구조다).
 *
 * 앱이 쓰는 순서는 **상태 → 주행 → 충전**으로 덧칠하는 것이다. 주행과 충전이 동시에
 * 열리는 일은 없다.
 */
data class TeslaStateTimelineResponse(
    /** 받은 창을 되돌려 싣는다 — 앱이 무엇을 받았는지 알 수 있게. */
    val days: Int,
    /** 창 시작(KST). **자정에 맞춰져 있다** — 앱이 하루에 한 행씩 그린다. */
    val from: LocalDateTime,
    /** 창 끝(KST) = 요청 시각. 진행 중인 구간의 `to`가 이 값이다. */
    val to: LocalDateTime,
    /**
     * `states`의 구간, 오래된 것부터. **창 밖은 잘려서 온다.**
     *
     * 그 기간에 기록이 없으면 빈 배열이다 — 404가 아니다(「없는 리소스」가 아니라
     * 「그 기간에 기록이 없다」).
     */
    val states: List<StateSegment>,
    /** 주행 구간. **마감되지 않은 유령 세션은 빠진다.** */
    val drives: List<TimeSegment>,
    /** 충전 구간. 유령 규칙은 `drives`와 같다. */
    val charges: List<TimeSegment>,
)

/**
 * `state`는 `online`·`offline`·`asleep`이다. **`/tesla/status`와 달리 `charging`·`driving`이
 * 여기 오지 않는다** — 그 둘은 `states` 테이블에 없고, 이 응답에서는 별도 배열로 나간다.
 *
 * `asleep`이 최근 며칠에 하나도 없을 수 있다(실측 최근 7일 0개). **그래도 앱의 색 팔레트에서
 * 빼지 않는다** — 2026년에도 2월·4월·5월·6월·7월에 있었고, 창에 안 잡히는 것뿐이다.
 */
data class StateSegment(
    val state: String,
    val from: LocalDateTime,
    val to: LocalDateTime,
)

/** 주행·충전 구간. 둘이 같은 모양이라 타입을 함께 쓴다. */
data class TimeSegment(
    val from: LocalDateTime,
    val to: LocalDateTime,
)
```

- [ ] **Step 2: `TeslaDriveInsightsResponse`에 필드 셋을 더한다**

`places` 아래에 이어 붙인다(기존 필드는 그대로 둔다):

```kotlin
    /**
     * 역대 최고 속도(km/h). **`months` 창을 따르지 않는다** — 창이 바뀔 때마다 바뀌면
     * 기록이 아니다. 앱은 이 값의 라벨을 **「역대 최고」**로 적어 옆 두 타일과 범위가
     * 다름을 글자로 드러낸다.
     *
     * 주행이 하나도 없으면 null이다 — 「역대 최고」라는 값 자체가 존재하지 않는다.
     * 아래 거리 둘과 반대다.
     *
     * **그 주행의 날짜(`maxSpeedAt`)를 함께 내지 않는다.** 실측 138 km/h가 최소 3건
     * 동률이라(2025-09-13, 2025-03-22, 2024-03-09) 하나를 골라 「그날 기록했다」고 하면
     * 거짓이 된다. 어느 것을 고를지 규칙을 만들 값어치가 없다.
     */
    val maxSpeedKmh: Int?,
    /**
     * 이번 달 주행거리(km, KST 경계). **`/tesla/summary`의 이번 달 `distanceKm`과 같은
     * 값이다** — 두 화면에 다른 숫자가 뜨면 어느 쪽도 못 믿는다.
     *
     * **0을 낸다. null이 아니다.** 이 저장소의 규칙은 「0은 안 탔다, null은 기록이 없다」인데
     * 이것은 기간이 못박힌 합계라 그 달에 주행이 없으면 「0km 탔다」가 사실이다. null로
     * 두면 매달 1일 새벽마다 화면에 「—」가 뜬다.
     */
    val monthDistanceKm: BigDecimal,
    /** 올해 주행거리(km, KST 경계). `monthDistanceKm`과 같은 이유로 0을 낸다. */
    val yearDistanceKm: BigDecimal,
```

- [ ] **Step 3: 컴파일 실패를 확인한다**

Run: `./gradlew :daily-record:compileKotlin`
Expected: FAIL — `TeslaVehicleService.driveInsights`가 `TeslaDriveInsightsResponse`를 만들 때 새 인자 셋이 없다는 오류. Task 5가 없앤다.

---

### Task 5: 서비스와 컨트롤러

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleService.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleController.kt`

**Interfaces:**
- Consumes: Task 1의 `TeslaTime.timelineWindowKst`, Task 2의 리포지토리 넷, Task 4의 DTO
- Produces:
  - `TeslaVehicleService.stateTimeline(days: Int): TeslaStateTimelineResponse`
  - `TeslaVehicleService.MIN_DAYS = 1`, `MAX_DAYS = 30` (companion object)
  - `GET /tesla/state-timeline?days=7`

- [ ] **Step 1: 서비스에 `stateTimeline`을 더한다**

`driveInsights` 아래, `resolveState` 위에:

```kotlin
    /**
     * 쿼리 셋이 전부다. **서버는 세 계열을 그대로 내고 겹침 해소는 화면이 한다** —
     * 하나의 띠로 합치려면 구간 산술이 필요한데 그 로직은 SQL로도 코틀린으로도 만만치 않다.
     *
     * 서비스가 하는 일은 **창 계산과 KST 되돌리기뿐**이다. 창 자르기(`GREATEST`/`LEAST`)와
     * 유령 거르기(24시간 창)는 SQL이 한다.
     */
    fun stateTimeline(days: Int): TeslaStateTimelineResponse {
        if (days !in MIN_DAYS..MAX_DAYS) {
            throw CustomException(ErrorCode.INVALID_REQUEST, "days는 $MIN_DAYS~$MAX_DAYS 사이여야 합니다")
        }

        val (fromKst, toKst) = TeslaTime.timelineWindowKst(days)
        val windowStart = TeslaTime.toUtc(fromKst)
        val windowEnd = TeslaTime.toUtc(toKst)

        return TeslaStateTimelineResponse(
            days = days,
            from = fromKst,
            to = toKst,
            states =
                vehicleRepository.stateSegments(windowStart, windowEnd).map {
                    StateSegment(state = it.state, from = TeslaTime.toKst(it.fromUtc), to = TeslaTime.toKst(it.toUtc))
                },
            drives = vehicleRepository.driveSegments(windowStart, windowEnd).map { it.toSegment() },
            charges = vehicleRepository.chargeSegments(windowStart, windowEnd).map { it.toSegment() },
        )
    }
```

`statOf` 아래에 변환 하나를 더한다:

```kotlin
    private fun SegmentRow.toSegment() = TimeSegment(from = TeslaTime.toKst(fromUtc), to = TeslaTime.toKst(toUtc))
```

- [ ] **Step 2: `driveInsights`에 통계 셋을 싣는다**

`driveInsights` 본문에서 버킷 맵을 만드는 두 줄 아래에 한 줄을 더한다:

```kotlin
        val stats = vehicleRepository.driveStats()
```

그리고 `TeslaDriveInsightsResponse(...)`의 `places = ...` 아래에:

```kotlin
            maxSpeedKmh = stats.maxSpeedKmh,
            monthDistanceKm = stats.monthDistanceKm,
            yearDistanceKm = stats.yearDistanceKm,
```

- [ ] **Step 3: 창 상수를 더한다**

`companion object`의 `MAX_MONTHS` 아래에:

```kotlin
        /** `/tesla/state-timeline`의 창. 기본 7일, 1~30. 상한이 30인 것은 응답 크기 때문이다 —
         * 30일이면 상태 구간이 600개 안팎이고, 그 정도는 한 응답으로 충분하다. */
        const val MIN_DAYS = 1
        const val MAX_DAYS = 30
```

- [ ] **Step 4: 컨트롤러에 엔드포인트를 더한다**

`driveInsights` 아래에:

```kotlin
    /**
     * **세 계열을 한 응답에 싣는다.** 상태·주행·충전을 나누면 같은 화면이 세 번 부르고
     * 그중 둘은 나머지 하나를 기다린다 — `/tesla/drive-insights`가 네 카드를 함께 싣는 것과
     * 같은 이유다.
     *
     * 앱은 이 응답을 캐시하지 않는다 — 「최근 7일」이 계속 움직인다.
     */
    @GetMapping("/state-timeline")
    @Operation(summary = "상태 타임라인 — 최근 며칠의 상태·주행·충전 구간")
    fun stateTimeline(
        @Parameter(description = "거슬러 볼 일수(1~30)", example = DEFAULT_DAYS)
        @RequestParam(defaultValue = DEFAULT_DAYS)
        days: Int,
    ): ResponseEntity<DataResponseBody<TeslaStateTimelineResponse>> = ResponseEntity.ok(DataResponseBody(service.stateTimeline(days)))
```

파일 끝의 `DEFAULT_MONTHS` 아래에:

```kotlin
private const val DEFAULT_DAYS = "7"
```

그리고 클래스 KDoc의 엔드포인트 목록에 `/tesla/state-timeline`을 더한다(「다섯 엔드포인트」 → 「여섯 엔드포인트」).

- [ ] **Step 5: 컴파일을 확인한다**

Run: `./gradlew :daily-record:compileKotlin`
Expected: PASS

- [ ] **Step 6: 기존 테스트가 깨진 것을 확인한다**

Run: `./gradlew :daily-record:test --tests 'com.toy.backend.tesla.TeslaVehicleServiceTest'`
Expected: FAIL — `driveInsights`를 부르는 기존 Given 넷이 `driveStats()` 스텁이 없어 mockk의 「no answer found」로 죽는다. Task 6이 고친다.

---

### Task 6: 서비스 테스트

**Files:**
- Modify: `apps/daily-record/src/test/kotlin/com/toy/backend/tesla/TeslaVehicleServiceTest.kt`

**Interfaces:**
- Consumes: Task 5의 `stateTimeline`, Task 4의 DTO 전부
- Produces: 없음

- [ ] **Step 1: 기존 `driveInsights` 테스트에 스텁을 더한다**

`driveInsights`를 부르는 Given 넷(「리포지토리가 아무 행도 주지 않으면」·「일부 버킷에만 주행이 있을 때」·「cars.efficiency가 null일 때」·「months가 범위 경계일 때」·「months=3으로 조회할 때」)의 `every { vehicleRepository.carEfficiency() }` 줄 옆에 각각 한 줄을 더한다:

```kotlin
            every { vehicleRepository.driveStats() } returns DriveStatsRow(138, BigDecimal("1331.3"), BigDecimal("13440.4"))
```

- [ ] **Step 2: 주행 통계 테스트를 더한다**

`months=3으로 조회할 때` Given 아래에:

```kotlin
        // 셋의 창이 서로 다르다 — 최고 속도는 전 기간이고 거리 둘은 KST 월·연 경계다.
        // 서비스는 그것을 해석하지 않고 그대로 올린다.
        Given("주행 통계가 올 때") {
            every { vehicleRepository.driveTemperatureBuckets(any()) } returns emptyList()
            every { vehicleRepository.driveTimes(any()) } returns emptyList()
            every { vehicleRepository.driveDistanceBuckets(any()) } returns emptyList()
            every { vehicleRepository.drivePlaces(any()) } returns emptyList()
            every { vehicleRepository.carEfficiency() } returns null
            every { vehicleRepository.driveStats() } returns
                DriveStatsRow(138, BigDecimal("1331.3"), BigDecimal("13440.4"))

            val response = service.driveInsights(12)

            Then("세 값이 그대로 실린다") {
                response.maxSpeedKmh shouldBe 138
                response.monthDistanceKm shouldBe BigDecimal("1331.3")
                response.yearDistanceKm shouldBe BigDecimal("13440.4")
            }
        }

        // 주행이 하나도 없는 경우다. 「역대 최고」는 값 자체가 없지만(null),
        // 거리 둘은 기간이 못박힌 합계라 0이 사실이다 — 서비스가 null로 바꾸지 않는다.
        Given("주행이 하나도 없을 때") {
            every { vehicleRepository.driveTemperatureBuckets(any()) } returns emptyList()
            every { vehicleRepository.driveTimes(any()) } returns emptyList()
            every { vehicleRepository.driveDistanceBuckets(any()) } returns emptyList()
            every { vehicleRepository.drivePlaces(any()) } returns emptyList()
            every { vehicleRepository.carEfficiency() } returns null
            every { vehicleRepository.driveStats() } returns
                DriveStatsRow(null, BigDecimal.ZERO, BigDecimal.ZERO)

            val response = service.driveInsights(12)

            Then("최고 속도는 null이고 거리 둘은 0이다") {
                response.maxSpeedKmh shouldBe null
                response.monthDistanceKm shouldBe BigDecimal.ZERO
                response.yearDistanceKm shouldBe BigDecimal.ZERO
            }
        }
```

- [ ] **Step 3: 타임라인 테스트를 더한다**

그 아래에:

```kotlin
        Given("상태 타임라인을 조회할 때") {
            val start = slot<LocalDateTime>()
            val end = slot<LocalDateTime>()
            every { vehicleRepository.stateSegments(capture(start), capture(end)) } returns emptyList()
            every { vehicleRepository.driveSegments(any(), any()) } returns emptyList()
            every { vehicleRepository.chargeSegments(any(), any()) } returns emptyList()

            val response = service.stateTimeline(7)

            // 앱이 하루에 한 행씩 그린다. 창이 임의 시각에서 시작하면 첫 행이 반쪽이 된다.
            // 고정 시각 검증은 `TeslaTimeTest`가 한다 — 여기서는 자정에 맞았는지만 본다.
            Then("창 시작이 KST 자정에서 6일을 뺀 자정이다") {
                response.from shouldBe LocalDate.now(KST).minusDays(6).atStartOfDay()
            }

            // KST 자정을 UTC로 옮기면 전날 15시다. 리포지토리는 UTC로 받아야 한다 —
            // KST로 넘기면 TeslaMate의 타임존 없는 컬럼과 9시간 어긋난다.
            Then("리포지토리는 UTC 경계를 받는다") {
                start.captured shouldBe TeslaTime.toUtc(response.from)
                end.captured shouldBe TeslaTime.toUtc(response.to)
            }

            // 「그 기간에 기록이 없다」는 404가 아니다.
            Then("세 배열이 비어도 응답이 성립한다") {
                response.days shouldBe 7
                response.states shouldBe emptyList()
                response.drives shouldBe emptyList()
                response.charges shouldBe emptyList()
            }
        }

        Given("구간이 UTC로 올 때") {
            every { vehicleRepository.stateSegments(any(), any()) } returns
                listOf(
                    StateSegmentRow("offline", LocalDateTime.of(2026, 8, 12, 15, 0), LocalDateTime.of(2026, 8, 12, 21, 14)),
                    StateSegmentRow("online", LocalDateTime.of(2026, 8, 12, 21, 14), LocalDateTime.of(2026, 8, 12, 21, 26)),
                )
            every { vehicleRepository.driveSegments(any(), any()) } returns
                listOf(SegmentRow(LocalDateTime.of(2026, 8, 12, 21, 18), LocalDateTime.of(2026, 8, 12, 21, 36)))
            every { vehicleRepository.chargeSegments(any(), any()) } returns
                listOf(SegmentRow(LocalDateTime.of(2026, 8, 15, 13, 11), LocalDateTime.of(2026, 8, 15, 20, 40)))

            val response = service.stateTimeline(7)

            // UTC + 9h = KST. 빠뜨리면 새벽 6시 출발이 밤 9시로 찍힌다.
            Then("세 배열의 시각이 전부 KST로 바뀐다") {
                response.states.first().from shouldBe LocalDateTime.of(2026, 8, 13, 0, 0)
                response.states.first().to shouldBe LocalDateTime.of(2026, 8, 13, 6, 14)
                response.drives.first().from shouldBe LocalDateTime.of(2026, 8, 13, 6, 18)
                response.charges.first().to shouldBe LocalDateTime.of(2026, 8, 16, 5, 40)
            }

            // 상류가 값을 늘리면 그대로 올라온다. 서버는 번역하지 않는다.
            Then("state 문자열은 그대로 나간다") {
                response.states.map { it.state } shouldBe listOf("offline", "online")
            }
        }

        Given("days가 범위 경계일 때") {
            every { vehicleRepository.stateSegments(any(), any()) } returns emptyList()
            every { vehicleRepository.driveSegments(any(), any()) } returns emptyList()
            every { vehicleRepository.chargeSegments(any(), any()) } returns emptyList()

            Then("1과 30은 통과한다") {
                service.stateTimeline(1).days shouldBe 1
                service.stateTimeline(30).days shouldBe 30
            }

            Then("0과 31은 400이다") {
                shouldThrow<CustomException> { service.stateTimeline(0) }
                    .errorCode shouldBe ErrorCode.INVALID_REQUEST
                shouldThrow<CustomException> { service.stateTimeline(31) }
                    .errorCode shouldBe ErrorCode.INVALID_REQUEST
            }
        }
```

파일 상단 import에 둘을 더한다:

```kotlin
import java.time.LocalDate
import java.time.ZoneId
```

그리고 spec 본문 첫 줄(`val vehicleRepository = ...` 위)에:

```kotlin
        val KST = ZoneId.of("Asia/Seoul")
```

- [ ] **Step 4: 전부 통과를 확인한다**

Run: `./gradlew :daily-record:test --tests 'com.toy.backend.tesla.*'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
./gradlew spotlessApply
git add apps/daily-record/src/main/kotlin/com/toy/backend/tesla/ \
        apps/daily-record/src/test/kotlin/com/toy/backend/tesla/
git commit -m "feat: 상태 타임라인을 내고 주행 통계 셋을 더한다"
```

---

### Task 7: 새 심볼이 어느 파일에 나오는지 센다

AGENTS.md의 「커밋 전」 검사다. **필드를 더했으면 응답으로 나가는 타입에 있는지 대 본다.**

- [ ] **Step 1: 세 필드가 응답 DTO까지 갔는지 확인한다**

```bash
for s in maxSpeedKmh monthDistanceKm yearDistanceKm; do
  echo "## $s"
  grep -rln "$s" --include='*.kt' apps/daily-record/src
done
```

Expected: 셋 다 목록에 `TeslaVehicleRows.kt`(행), `JdbcTeslaVehicleRepository.kt`(매핑), `TeslaVehicleService.kt`(옮기기), **`TeslaVehicleDtos.kt`(응답)**, `TeslaVehicleServiceTest.kt`가 있어야 한다. `TeslaVehicleDtos.kt`가 없으면 그 값은 앱까지 가지 않는다.

- [ ] **Step 2: 타임라인 타입이 컨트롤러까지 갔는지 확인한다**

```bash
grep -rln "TeslaStateTimelineResponse" --include='*.kt' apps/daily-record/src
```

Expected: `TeslaVehicleDtos.kt`, `TeslaVehicleService.kt`, `TeslaVehicleController.kt`. 컨트롤러가 없으면 엔드포인트가 없는 것이다.

- [ ] **Step 3: 전체 빌드를 돌린다**

Run: `./gradlew :daily-record:build`
Expected: PASS (spotless 포함)

- [ ] **Step 4: 실제로 앱을 띄워 두 엔드포인트를 호출한다**

단위 테스트는 리포지토리를 목으로 대체하므로 **SQL이 실제로 도는지 잡지 못한다.** 이 계획은 SQL 넷을 새로 썼으므로 이 단계가 필수다. AGENTS.md의 「단위 테스트는 …잡지 못한다」 항목이 가리키는 자리다.

TeslaMate DB는 라즈베리파이에 있다. 접속 정보가 없으면 이 단계는 사용자가 배포 후 확인하고, 확인할 값은 스펙의 실측값이다:

- `GET /tesla/state-timeline?days=7` → `states` 145개 안팎, `drives` 22개 안팎, `charges` 1개. **`drives`에 창을 가로지르는 7일짜리 구간이 있으면 유령 필터가 안 먹은 것이다.**
- `GET /tesla/drive-insights` → `maxSpeedKmh` 138, `monthDistanceKm`이 `GET /tesla/summary`의 이번 달 `distanceKm`과 **같은 값**
- `GET /tesla/state-timeline?days=0` → 400

---

## Self-Review

**스펙 커버리지**

| 스펙 요구 | 태스크 |
|---|---|
| `GET /tesla/state-timeline?days=7`, 1~30, 기본 7 | Task 5 (컨트롤러·검증) |
| 세 배열을 그대로 낸다 | Task 4 (DTO), Task 5 (서비스) |
| 창 = KST 자정 − (days−1)일 ~ 요청 시각 | Task 1, Task 6 검증 |
| 유령 세션 24시간 룰 (drives·charging_processes) | Task 3 (`SEGMENT_NOT_GHOST`) |
| `states`에는 유령 룰을 적용하지 않는다 | Task 3 (`STATE_SEGMENTS_SQL`에 빠져 있음) |
| 구간을 창에 맞춰 자른다 (`GREATEST`/`LEAST`) | Task 3 (`SEGMENT_COLUMNS`) |
| `:windowStart`·`:windowEnd`를 UTC로 넘긴다 | Task 5 (`TeslaTime.toUtc`), Task 6 검증 |
| `maxSpeedKmh` nullable, `months` 창 무관 | Task 2·3·4, Task 6 검증 |
| 월·연 거리 `COALESCE(…, 0)` | Task 3, Task 6 검증 |
| `/tesla/summary`와 같은 숫자 | Task 3 (`DRIVE_STATS_SQL`이 `DRIVE_MONTHLY_SQL`과 조건 일치), Task 7 Step 4 |
| `maxSpeedAt`을 두지 않는다 | 어느 태스크에도 없음 (의도) |
| 응답 시각 KST 변환 | Task 5, Task 6 검증 |
| `days` 범위 밖 400 | Task 5, Task 6 검증 |
| 빈 결과가 404가 아니다 | Task 6 검증 |
| 페이지네이션·다운샘플링 없음 | 어느 태스크에도 없음 (의도) |
| `car_id`를 두지 않는다 | Global Constraints |

**타입 일관성**

- `SegmentRow`는 Task 2에서 정의하고 Task 3·5·6에서 같은 이름으로 쓴다.
- `StateSegmentRow`는 `stateSegments`의 반환 타입이다 — Task 2 Step 2의 주석 블록에 `List<SegmentRow>`로 잘못 적힐 여지가 있어 그 아래에 경고를 달아 두었다.
- `TimeSegment`(응답)와 `SegmentRow`(행)는 이름이 다르다. 응답은 KST, 행은 UTC라는 기존 경계를 그대로 따른 것이다.
- `MIN_DAYS`/`MAX_DAYS`는 Task 5에서 정의하고 Task 6에서 경계값(1·30·0·31)으로만 쓴다.
