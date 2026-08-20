# TeslaMate 통계 응답 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 앱 통계 탭 한 장을 한 번에 채우는 `GET /tesla/insights`(기존 `/tesla/drive-insights`의 여덟 필드를 이름까지 그대로 흡수한다)와, 개요 화면의 배터리 창을 내는 `GET /tesla/battery-window`를 신설한다.

**Architecture:** `com.toy.backend.tesla`에 **세 번째 파일 계열 `TeslaInsights*`를 만든다.** 지금은 충전(`TeslaCharge*`)과 차량(`TeslaVehicle*`) 둘인데, `JdbcTeslaVehicleRepository`가 이미 664줄이라 새 집계 열넷을 얹으면 1,200줄을 넘고 「차가 어떤 상태인가」와 「26장짜리 통계 화면」이 한 파일에서 섞인다. 계열을 가르되 **기존 SQL은 복사하지 않는다** — `TeslaInsightsService`가 `TeslaVehicleRepository`를 함께 주입받아 온도·시간대·거리·장소·주행통계·전비 여섯 개를 그대로 쓴다. 집계·창 자르기·KST 변환의 경계는 기존 규약 그대로다: **SQL이 자르고 합치고, 서비스는 자리를 채우고 KST로 되돌리고 뺄셈만 한다.**

**Tech Stack:** Kotlin, Spring Boot, `JdbcClient`(TeslaMate 보조 DataSource), kotest `BehaviorSpec` + mockk

**Spec:** `docs/superpowers/specs/2026-08-20-tesla-insights-design.md`

## Global Constraints

- **TeslaMate 시각은 타임존 없는 `timestamp`에 든 UTC 값이다.** SQL에서 `now()`(timestamptz)와 직접 비교하지 않고 `(now() AT TIME ZONE 'UTC')`로 맞춘다. 행 타입(`*Row`)의 시각은 전부 UTC이고, KST로 되돌리는 것은 서비스가 `TeslaTime.toKst`로 한다.
- **월 경계는 KST로 자른다.** `date_trunc('month', x AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Seoul')`.
- **유령 행을 뺀다.** 주행·충전 모두 `end_date IS NOT NULL`. 이 DB에 마감되지 않은 주행 12건·충전 6건이 있다.
- **`@Transactional`을 붙이지 않는다.** 기본 트랜잭션 매니저는 daily-record 커넥션의 것이라 TeslaMate SQL에 효력이 없다.
- **nullable 정수·불리언은 `rs.getObject`로 읽는다.** `rs.getInt`는 SQL NULL에 0을 준다.
- **나눗셈을 하지 않는다.** 분자와 분모를 따로 낸다. 유일한 예외는 버킷을 «고르기 위한» 나눗셈(평균 속도)이고 그 값은 응답에 나가지 않는다.
- **캐시를 두지 않는다.** 사용자 2명·하루 수십 건이다.
- `car_id`를 파라미터로도 응답으로도 두지 않는다. 차량이 1대다.
- 컨트롤러 단위 테스트는 이 저장소 관례대로 쓰지 않는다.
- **커밋 전 `./gradlew spotlessApply` 필수.** 커밋 메시지는 한국어 현재형(`feat:`/`refactor:`/`docs:`).

---

## File Structure

| 파일 | 이 계획에서의 책임 |
|---|---|
| `TeslaTime.kt` (수정) | 통계 창 계산·달별 경과 분·요일별 경과 분. **전부 순수 함수** — 정지 시간이 뺄셈이라 시각을 못 박고 검증해야 한다 |
| `TeslaBuckets.kt` (신규) | 버킷 **라벨** 다섯 벌(온도·거리·최고속도·평균속도·충전 SoC). 지금 `TeslaVehicleService`의 private 상수 둘이 여기로 옮겨 오고, 두 서비스가 함께 본다 |
| `TeslaInsightsRows.kt` (신규) | 새 집계의 행 타입 |
| `TeslaInsightsRepository.kt` (신규) | 새 집계의 인터페이스 |
| `JdbcTeslaInsightsRepository.kt` (신규) | 위의 SQL과 매핑 |
| `TeslaInsightsDtos.kt` (신규) | `TeslaInsightsResponse`·`TeslaBatteryWindowResponse`와 그 하위 타입 |
| `TeslaInsightsService.kt` (신규) | `insights(months)`·`batteryWindow(hours)` |
| `TeslaInsightsController.kt` (신규) | `GET /tesla/insights`·`GET /tesla/battery-window` |
| `TeslaInsightsServiceTest.kt` (신규) | 위 둘의 테스트 |
| `TeslaVehicleRepository.kt`·`JdbcTeslaVehicleRepository.kt` (수정) | 네 메서드의 `months: Int`를 **명시 UTC 범위**로 바꾼다 |
| `TeslaVehicleService.kt` (수정) | 옮겨 간 버킷 상수를 `TeslaBuckets`에서 읽고, `driveInsights`가 자기 범위를 계산해 넘긴다 |
| `TeslaVehicleController.kt` (수정) | 클래스 KDoc에서 새 계열을 가리킨다 |
| `TeslaTimeTest.kt`·`TeslaVehicleServiceTest.kt` (수정) | 위 변경에 맞춘다 |

**왜 리포지토리를 둘로 나눠 놓고 서비스는 둘 다 주입받나.** 계열을 가르는 기준은 「어떤 질문에 답하는가」인데, 온도별 전비·거리 분포·자주 가는 곳은 **두 화면이 같은 질문을 한다.** SQL을 복사하면 `/tesla/drive-insights`를 지울 때 어느 쪽이 원본인지 알 수 없게 되고, 지금 고쳐야 할 버킷 경계가 두 곳에 생긴다. 주입이 하나 느는 대가로 그 둘을 막는다.

---

## 실측 요약 (2026-08-20, 파이 TeslaMate DB)

아래 숫자가 이 계획의 SQL과 버킷 경계를 정했다. 근거 전문은 스펙의 「실측」 절에 있다.

| 잰 것 | 결과 | 이 계획에서 쓰는 곳 |
|---|---|---|
| 완료 주행 | 5,058건, 2021-09-03 ~ 2026-08-20 (60개월) | `months=0` 상한 60 |
| `drives` NULL | `speed_max`·`duration_min`·`distance`·`start_rated_range_km`·`outside_temp_avg` 전부 0건 | 행 타입을 non-null로 둔다 |
| `speed_max` 분포 | 140 이상 0건 | 최고속도 버킷 **7칸** |
| 평균 속도 분포 | 100 이상 0건 | 평균속도 버킷 **5칸** |
| 충전 종료 SoC | 정확히 100%가 71건 | SoC 버킷 마지막 칸은 **양끝 닫힘** |
| `geofences` | 0행. 이름은 주소가 채운다(충전소 7~12곳이 나온다) | `places`·`chargers`의 COALESCE |
| 순수 주차 구간 | 월 14~99건, 하락 월 75~169km | 표본 충분 |
| 주차 구간 중 음수 | 3,960 : 628 | **0으로 자르지 않는다** |
| 48시간 `positions` | 12,517행 / 32ms | **5분 슬롯으로 솎아 82개** |
| `usable_battery_level` | 최근 30일 3.0%만 채워짐 | nullable, 보조 계열 |

---

### Task 1: 통계 창·경과 시간 계산 (`TeslaTime`)

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaTime.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/tesla/TeslaTimeTest.kt`

**Interfaces:**
- Consumes: 기존 `TeslaTime.toUtc`·`toKst`·`monthRangeUtc`·`nowKst`
- Produces:
  - `TeslaTime.WeekdaySpan(occurrences: Int, elapsedMin: Int)` — data class
  - `TeslaTime.monthElapsedMinutes(month: YearMonth, startKst: LocalDateTime, endKst: LocalDateTime): Int`
  - `TeslaTime.weekdaySpans(startKst: LocalDateTime, endKst: LocalDateTime): Map<Int, WeekdaySpan>` — 키는 **1=월요일**(ISO)

**왜 순수 함수인가:** 정지 시간이 「경과 − 주행 − 충전」이라 **분모가 시각에 달렸다.** 8월 20일에 8월의 분모를 44,640분(달 전체)으로 잡으면 아직 오지 않은 11일치가 정지 시간으로 들어가 그 달만 막대가 솟는다. 이 저장소에는 `Clock` 주입 관례가 없고 `LocalDate.now()`를 직접 쓰므로, 관례를 깨지 않으면서 검증하려면 시각을 인자로 받는 순수 함수와 `now()`를 읽는 얇은 함수를 가르는 것이 가장 싸다 — `timelineWindowKst`가 이미 같은 꼴이다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`TeslaTimeTest.kt`의 기존 `BehaviorSpec` 본문 끝에 이어 붙인다:

```kotlin
    given("monthElapsedMinutes — 달의 경과 분") {
        val start = LocalDateTime.of(2026, 3, 1, 0, 0)

        `when`("이미 끝난 달이면") {
            then("그 달 전체 분이다") {
                // 2026-04는 30일 = 43,200분
                TeslaTime.monthElapsedMinutes(YearMonth.of(2026, 4), start, LocalDateTime.of(2026, 8, 20, 15, 0)) shouldBe 43_200
            }
        }

        `when`("진행 중인 달이면") {
            then("지금까지만 센다") {
                // 8/1 00:00 ~ 8/20 15:00 = 19일 15시간 = 28,260분
                TeslaTime.monthElapsedMinutes(YearMonth.of(2026, 8), start, LocalDateTime.of(2026, 8, 20, 15, 0)) shouldBe 28_260
            }
        }

        `when`("창이 달 도중에 시작하면") {
            then("창 시작부터 센다") {
                // 3/1 00:00 시작이 아니라 3/10 12:00 시작이면 3/10 12:00 ~ 3/31 24:00 = 21일 12시간
                TeslaTime.monthElapsedMinutes(
                    YearMonth.of(2026, 3),
                    LocalDateTime.of(2026, 3, 10, 12, 0),
                    LocalDateTime.of(2026, 8, 20, 15, 0),
                ) shouldBe 30_960
            }
        }

        `when`("창 밖의 달이면") {
            then("0이다 — 음수가 나오지 않는다") {
                TeslaTime.monthElapsedMinutes(YearMonth.of(2025, 1), start, LocalDateTime.of(2026, 8, 20, 15, 0)) shouldBe 0
                TeslaTime.monthElapsedMinutes(YearMonth.of(2027, 1), start, LocalDateTime.of(2026, 8, 20, 15, 0)) shouldBe 0
            }
        }
    }

    given("weekdaySpans — 요일별 등장 수와 경과 분") {
        `when`("정확히 한 주면") {
            val spans =
                TeslaTime.weekdaySpans(
                    LocalDateTime.of(2026, 8, 10, 0, 0), // 월요일
                    LocalDateTime.of(2026, 8, 17, 0, 0), // 다음 월요일 00:00
                )

            then("일곱 요일이 한 번씩, 하루씩이다") {
                spans.keys shouldBe (1..7).toSet()
                spans.values.map { it.occurrences }.toSet() shouldBe setOf(1)
                spans.values.map { it.elapsedMin }.toSet() shouldBe setOf(1_440)
            }
        }

        `when`("오늘이 아직 안 끝났으면") {
            val spans =
                TeslaTime.weekdaySpans(
                    LocalDateTime.of(2026, 8, 17, 0, 0), // 월요일 00:00
                    LocalDateTime.of(2026, 8, 20, 15, 0), // 목요일 15:00
                )

            then("오늘도 한 번으로 세되 경과 분은 지금까지다") {
                spans[4]!!.occurrences shouldBe 1 // 목요일
                spans[4]!!.elapsedMin shouldBe 900 // 15시간
                spans[1]!!.elapsedMin shouldBe 1_440 // 월요일은 온전히 지났다
            }
        }

        `when`("끝이 시작보다 이르면") {
            then("빈 맵이 아니라 전부 0이다") {
                val spans =
                    TeslaTime.weekdaySpans(
                        LocalDateTime.of(2026, 8, 20, 0, 0),
                        LocalDateTime.of(2026, 8, 19, 0, 0),
                    )
                spans.keys shouldBe (1..7).toSet()
                spans.values.map { it.occurrences }.toSet() shouldBe setOf(0)
            }
        }
    }
```

`TeslaTimeTest.kt` 상단 import에 `java.time.YearMonth`를 더한다.

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :apps:daily-record:test --tests '*TeslaTimeTest*'`
Expected: FAIL — `Unresolved reference: monthElapsedMinutes`

- [ ] **Step 3: 최소 구현을 쓴다**

`TeslaTime.kt`의 `timelineWindowKst` 아래에 더한다:

```kotlin
    /**
     * 창 안에서 그 달이 실제로 몇 분 지났는가. **정지 시간의 분모다.**
     *
     * 「그 달 전체 분」이 아닌 이유: 8월 20일에 8월의 분모를 달 전체(44,640분)로 잡으면 아직
     * 오지 않은 11일치가 정지 시간으로 들어가 **진행 중인 달만 막대가 솟는다.** 창 시작보다
     * 앞선 부분도 같은 이유로 뺀다.
     *
     * 창과 겹치지 않는 달은 0이다 — 뺄셈의 분모라 음수가 나오면 안 된다.
     */
    fun monthElapsedMinutes(
        month: YearMonth,
        startKst: LocalDateTime,
        endKst: LocalDateTime,
    ): Int {
        val from = maxOf(month.atDay(1).atStartOfDay(), startKst)
        val to = minOf(month.plusMonths(1).atDay(1).atStartOfDay(), endKst)
        if (!from.isBefore(to)) return 0
        return Duration.between(from, to).toMinutes().toInt()
    }

    /**
     * 창 안에서 각 요일이 며칠 나왔고(`occurrences`) 그 요일에 실제로 몇 분이 흘렀는가
     * (`elapsedMin`). **키는 1이 월요일**(ISO)이고 일곱 개가 늘 있다 — 0인 요일도 자리를 지킨다.
     *
     * `occurrences`는 요일 평균의 분모이고 `elapsedMin`은 정지 시간의 분모다. **둘을 함께 내는
     * 이유는 오늘이 반쪽이기 때문이다** — 오늘은 한 번으로 세지만(그날의 주행이 이미 분자에
     * 들어 있다) 흐른 시간은 지금까지뿐이다. 한 값으로 합치면 둘 중 하나가 틀린다.
     *
     * 하루씩 도는 구현이다. 60개월이 1,800회 남짓이라 잴 필요가 없다.
     */
    fun weekdaySpans(
        startKst: LocalDateTime,
        endKst: LocalDateTime,
    ): Map<Int, WeekdaySpan> {
        val occurrences = IntArray(8)
        val minutes = IntArray(8)
        var dayStart = startKst.toLocalDate().atStartOfDay()
        while (dayStart.isBefore(endKst)) {
            val dayEnd = dayStart.plusDays(1)
            val from = maxOf(dayStart, startKst)
            val to = minOf(dayEnd, endKst)
            if (from.isBefore(to)) {
                val weekday = dayStart.dayOfWeek.value
                occurrences[weekday]++
                minutes[weekday] += Duration.between(from, to).toMinutes().toInt()
            }
            dayStart = dayEnd
        }
        return (1..7).associateWith { WeekdaySpan(occurrences[it], minutes[it]) }
    }

    /** 요일 하나가 창 안에서 며칠 나왔고 몇 분이 흘렀는지. 자세한 뜻은 `weekdaySpans`. */
    data class WeekdaySpan(
        val occurrences: Int,
        val elapsedMin: Int,
    )
```

`TeslaTime.kt` 상단 import에 `java.time.Duration`을 더한다.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :apps:daily-record:test --tests '*TeslaTimeTest*'`
Expected: PASS

- [ ] **Step 5: 커밋한다**

```bash
./gradlew spotlessApply
git add apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaTime.kt \
        apps/daily-record/src/test/kotlin/com/toy/backend/tesla/TeslaTimeTest.kt
git commit -m "feat: 통계 창의 달별·요일별 경과 시간을 낸다"
```

---

### Task 2: 버킷 라벨을 한곳으로 모은다 (`TeslaBuckets`)

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaBuckets.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleService.kt`

**Interfaces:**
- Consumes: 없음
- Produces:
  - `TeslaBuckets.TEMPERATURE: List<Pair<Int, Pair<Int?, Int?>>>` — 5칸, `bucket → (fromC, toC)`
  - `TeslaBuckets.DISTANCE: List<Pair<Int, Pair<Int, Int?>>>` — 5칸, `bucket → (fromKm, toKm)`
  - `TeslaBuckets.SPEED: List<Pair<Int, Pair<Int, Int?>>>` — 7칸, `bucket → (fromKmh, toKmh)`
  - `TeslaBuckets.SPEED_ENERGY: List<Pair<Int, Pair<Int, Int?>>>` — 5칸, `bucket → (fromKmh, toKmh)`
  - `TeslaBuckets.CHARGE_LEVEL: List<Pair<Int, Pair<Int, Int>>>` — 10칸, `bucket → (fromPct, toPct)`

**왜 지금 옮기나:** 온도·거리 라벨은 지금 `TeslaVehicleService`의 private 상수인데, `/tesla/insights`가 같은 라벨을 내야 한다. 복사하면 **SQL의 `CASE`와 어긋날 수 있는 자리가 둘에서 셋으로 는다.** 새 상수 셋을 더하는 김에 함께 모은다. 이 태스크는 동작을 바꾸지 않는다 — 기존 테스트가 그대로 통과해야 한다.

- [ ] **Step 1: 파일을 만든다**

`TeslaBuckets.kt`:

```kotlin
package com.toy.backend.tesla

/**
 * 버킷의 **응답 라벨**이다. 값은 `bucket` 번호 → (`from`, `to`)이고, 경계는 늘
 * **`from` 포함·`to` 미만**이다(하나 있는 예외는 `CHARGE_LEVEL`의 마지막 칸이다).
 *
 * **여기 숫자는 `JdbcTeslaVehicleRepository`·`JdbcTeslaInsightsRepository`의 `CASE`와 같아야
 * 한다** — 거기는 임계값으로, 여기는 라벨로 쓴다. 한쪽만 고치면 응답의 라벨과 실제 집계가
 * 어긋나고, **행 수는 그대로라 정상처럼 보인다.**
 *
 * 두 서비스가 함께 본다. `/tesla/drive-insights`(옛것)와 `/tesla/insights`(새것)가 같은 기간
 * 동안 나란히 살아 있고 같은 라벨을 내야 하기 때문이다.
 */
object TeslaBuckets {
    /** 온도(℃). 하한/상한이 없으면 null이다. */
    val TEMPERATURE: List<Pair<Int, Pair<Int?, Int?>>> =
        listOf(
            1 to (null to 0),
            2 to (0 to 10),
            3 to (10 to 20),
            4 to (20 to 30),
            5 to (30 to null),
        )

    /** 주행 거리(km). */
    val DISTANCE: List<Pair<Int, Pair<Int, Int?>>> =
        listOf(
            1 to (0 to 5),
            2 to (5 to 20),
            3 to (20 to 50),
            4 to (50 to 100),
            5 to (100 to null),
        )

    /**
     * 주행 한 건의 **최고 속도**(km/h). 20 폭에 일곱 칸이다 —
     * 실측(2026-08-20)으로 `speed_max`가 140 이상인 주행이 0건이라 `120~`이 마지막이다.
     */
    val SPEED: List<Pair<Int, Pair<Int, Int?>>> =
        listOf(
            1 to (0 to 20),
            2 to (20 to 40),
            3 to (40 to 60),
            4 to (60 to 80),
            5 to (80 to 100),
            6 to (100 to 120),
            7 to (120 to null),
        )

    /**
     * 주행 한 건의 **평균 속도**(km/h = 거리 ÷ 시간). 20 폭에 다섯 칸이다 —
     * 실측으로 평균 100km/h를 넘는 주행이 0건이라 `80~`이 마지막이다. 최고 속도보다 두 칸
     * 짧은 것이 맞다.
     */
    val SPEED_ENERGY: List<Pair<Int, Pair<Int, Int?>>> =
        listOf(
            1 to (0 to 20),
            2 to (20 to 40),
            3 to (40 to 60),
            4 to (60 to 80),
            5 to (80 to null),
        )

    /**
     * 충전의 시작·종료 SoC(%). 10 폭에 열 칸이다.
     *
     * **마지막 칸만 양끝이 닫힌다**(`90 이상 100 이하`). 다른 배열처럼 「`to` 미만」으로 두면
     * 정확히 100%로 끝난 충전이 어느 칸에도 안 들어가는데, 실측으로 그런 충전이 71건이다 —
     * 가장 흔한 값이 통째로 사라진다.
     */
    val CHARGE_LEVEL: List<Pair<Int, Pair<Int, Int>>> =
        (1..10).map { it to ((it - 1) * 10 to it * 10) }
}
```

- [ ] **Step 2: `TeslaVehicleService`가 새 상수를 보게 한다**

`TeslaVehicleService.driveInsights` 안의 `TEMPERATURE_BUCKETS`를 `TeslaBuckets.TEMPERATURE`로, `DISTANCE_BUCKETS`를 `TeslaBuckets.DISTANCE`로 바꾸고, `companion object`에서 두 private 상수와 그 KDoc을 지운다(내용은 `TeslaBuckets`로 옮겨 갔다). `EARTH_RADIUS_M`·`MIN_MONTHS`·`MAX_MONTHS`·`MIN_HOURS`·`MAX_HOURS`·`TREND_MONTHS`·`CHARGING`·`DRIVING`은 그대로 둔다.

- [ ] **Step 3: 기존 테스트가 그대로 통과하는지 본다**

Run: `./gradlew :apps:daily-record:test --tests '*Tesla*'`
Expected: PASS — 이 태스크는 동작을 바꾸지 않는다. 하나라도 깨지면 상수를 옮기며 숫자가 바뀐 것이다.

- [ ] **Step 4: 커밋한다**

```bash
./gradlew spotlessApply
git add apps/daily-record/src/main/kotlin/com/toy/backend/tesla/
git commit -m "refactor: 버킷 라벨을 TeslaBuckets 한곳으로 모은다"
```

---

### Task 3: 주행 네 집계를 명시 범위로 바꾼다 (`TeslaVehicleRepository`)

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleRepository.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/JdbcTeslaVehicleRepository.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleService.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/tesla/TeslaVehicleServiceTest.kt`

**Interfaces:**
- Consumes: `TeslaTime.nowKst`·`toUtc`
- Produces (네 메서드의 새 시그니처, `TeslaInsightsService`가 그대로 부른다):
  - `driveTemperatureBuckets(startUtc: LocalDateTime, endUtcExclusive: LocalDateTime): List<DriveTemperatureBucketRow>`
  - `driveTimes(startUtc: LocalDateTime, endUtcExclusive: LocalDateTime): List<DriveTimeRow>`
  - `driveDistanceBuckets(startUtc: LocalDateTime, endUtcExclusive: LocalDateTime): List<DriveDistanceBucketRow>`
  - `drivePlaces(startUtc: LocalDateTime, endUtcExclusive: LocalDateTime): List<DrivePlaceRow>`

**왜 바꾸나:** 지금 네 메서드는 `months: Int`를 받아 SQL 안에서 `end_date >= now() − N months`로 **구르는 창**을 만든다. `/tesla/insights`는 **달에 맞춘 창**이 필요하다(`monthly` 배열이 달 단위라, 한 응답 안에서 「12개월」의 뜻이 배열마다 다르면 안 된다). 게다가 `months=0`(전체 기간)을 SQL의 `now() − 0 months`로는 표현할 수 없다. 범위를 서비스가 계산해 넘기면 두 뜻이 다 표현되고 **창의 정의가 테스트 가능한 자리로 나온다.**

**`/tesla/drive-insights`의 동작은 바뀌지 않는다.** 서비스가 예전과 같은 구르는 창(`지금 − N개월` ~ `지금`)을 만들어 넘긴다. 앱 1단계가 이 엔드포인트를 계속 쓰므로 여기서 뜻을 바꾸면 안 된다.

- [ ] **Step 1: 인터페이스와 SQL을 고친다**

`TeslaVehicleRepository.kt`의 네 메서드 시그니처를 위 「Produces」대로 바꾸고, 각 KDoc의 「`months` 범위」 문장을 다음으로 바꾼다:

```kotlin
    /**
     * ... (기존 설명 유지) ...
     *
     * **범위를 서비스가 정해 넘긴다.** 예전에는 `months`를 받아 SQL이 `now() − N개월`로
     * 구르는 창을 만들었는데, `/tesla/insights`는 달에 맞춘 창이 필요하고 「전체 기간」도
     * 표현해야 한다. 경계 컬럼은 **`end_date`**다(월별 집계는 `start_date`를 쓴다 —
     * 자정을 걸친 주행 한 건의 차이다).
     */
```

`JdbcTeslaVehicleRepository.kt`에서 `DRIVE_WINDOW` 상수를 바꾼다:

```kotlin
        /**
         * 네 주행 쿼리가 함께 쓰는 조회 범위. **UTC로 온다** — TeslaMate가 타임존 없는 컬럼에
         * UTC 값을 넣으므로 KST를 넘기면 9시간이 어긋난다.
         *
         * 경계 컬럼이 `end_date`인 것은 초판 그대로다. 「범위에 든다」의 기준을 네 쿼리가
         * 같이 쓰게 하려는 것이다.
         */
        private const val DRIVE_WINDOW = """
                   AND d.end_date >= :start
                   AND d.end_date <  :end
        """
```

네 메서드의 `.param("months", months)`를 `.param("start", startUtc).param("end", endUtcExclusive)`로 바꾼다.

- [ ] **Step 2: 서비스가 범위를 만들어 넘기게 한다**

`TeslaVehicleService.driveInsights`의 검증 바로 뒤에 넣는다:

```kotlin
        // 예전 SQL이 `now() − N개월`로 만들던 창을 그대로 옮겼다. **뜻을 바꾸지 않는다** —
        // 앱 1단계가 이 엔드포인트를 계속 쓴다. 달에 맞춘 창은 `/tesla/insights`의 것이다.
        val nowKst = TeslaTime.nowKst()
        val endUtc = TeslaTime.toUtc(nowKst)
        val startUtc = TeslaTime.toUtc(nowKst.minusMonths(months.toLong()))
```

그리고 네 호출을 `vehicleRepository.driveTemperatureBuckets(startUtc, endUtc)` 꼴로 바꾼다.

- [ ] **Step 3: 기존 테스트의 목 시그니처를 맞춘다**

`TeslaVehicleServiceTest.kt`에서 네 메서드의 `every { ... }` 인자를 `any<LocalDateTime>(), any<LocalDateTime>()`으로 바꾼다. **범위 값 자체를 검증하지 않는다** — `nowKst()`가 실제 시각을 읽어 고정할 수 없고, 창의 계산은 Task 1이 `TeslaTime` 쪽에서 이미 못 박았다.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :apps:daily-record:test --tests '*Tesla*'`
Expected: PASS

- [ ] **Step 5: 커밋한다**

```bash
./gradlew spotlessApply
git add apps/daily-record/src/
git commit -m "refactor: 주행 집계의 조회 범위를 서비스가 정해 넘긴다"
```

---

### Task 4: `/tesla/insights` 골격 — 창 계산과 기존 여덟 필드

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaInsightsRepository.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/JdbcTeslaInsightsRepository.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaInsightsDtos.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaInsightsService.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaInsightsController.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/tesla/TeslaInsightsServiceTest.kt`

**Interfaces:**
- Consumes: `TeslaTime.nowKst`·`toUtc`, `TeslaBuckets.TEMPERATURE`·`DISTANCE`, `TeslaVehicleRepository.driveTemperatureBuckets`·`driveTimes`·`driveDistanceBuckets`·`drivePlaces`·`driveStats`·`carEfficiency`
- Produces:
  - `TeslaInsightsRepository.firstDriveMonth(): YearMonth?`
  - `TeslaInsightsService.insights(months: Int): TeslaInsightsResponse`
  - `TeslaInsightsService.InsightsWindow(fromMonth, toMonth, startKst, endKst, startUtc, endUtc)` — private, 뒤 태스크들이 이어 쓴다
  - `TeslaInsightsResponse` — 이 태스크에서는 `months`·`efficiencyKwhPerKm`·`temperatureBuckets`·`driveTimes`·`distanceBuckets`·`places`·`maxSpeedKmh`·`totalDistanceKm`·`recordedMonths`만 있고, 뒤 태스크가 필드를 더한다
  - `GET /tesla/insights?months=12`

**`months=0`을 어떻게 푸는가:** 서비스가 **모든 범위를 자기가 정하고 SQL은 `:start`·`:end`만 받는다.** `months=0`이면 `firstDriveMonth()`로 가장 오래된 달을 물어 거기서 시작한다. SQL 어디에도 「0이면 전체」라는 분기를 두지 않는다 — 그런 분기는 쿼리 수만큼 복제된다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`TeslaInsightsServiceTest.kt` 신규:

```kotlin
package com.toy.backend.tesla

import com.toy.backend.common.exception.CustomException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * 리포지토리를 목으로 두고 **서비스가 하는 일만** 본다 — 범위 계산, 빈 자리 채움,
 * 뺄셈(정지 시간), KST 되돌리기다. 집계·정렬·KST 자르기는 SQL의 일이라 여기서 안 본다.
 */
class TeslaInsightsServiceTest : BehaviorSpec({
    val insightsRepository = mockk<TeslaInsightsRepository>()
    val vehicleRepository = mockk<TeslaVehicleRepository>()
    val service = TeslaInsightsService(insightsRepository, vehicleRepository)

    fun stubEmpty() {
        every { insightsRepository.firstDriveMonth() } returns null
        every { vehicleRepository.driveTemperatureBuckets(any(), any()) } returns emptyList()
        every { vehicleRepository.driveTimes(any(), any()) } returns emptyList()
        every { vehicleRepository.driveDistanceBuckets(any(), any()) } returns emptyList()
        every { vehicleRepository.drivePlaces(any(), any()) } returns emptyList()
        every { vehicleRepository.driveStats() } returns DriveStatsRow(null, BigDecimal.ZERO, 0)
        every { vehicleRepository.carEfficiency() } returns BigDecimal("0.168")
    }

    given("insights — 범위 검증") {
        `when`("months가 61이면") {
            then("400이다") {
                shouldThrow<CustomException> { service.insights(61) }
            }
        }

        `when`("months가 음수면") {
            then("400이다") {
                shouldThrow<CustomException> { service.insights(-1) }
            }
        }

        `when`("months가 0이면") {
            then("전체 기간으로 해석돼 통과한다") {
                stubEmpty()
                service.insights(0).months shouldBe 0
            }
        }
    }

    given("insights — 버킷 자리 채움") {
        `when`("행이 하나도 없으면") {
            then("온도 다섯 칸·거리 다섯 칸이 0으로 자리를 지킨다") {
                stubEmpty()
                val response = service.insights(12)

                response.temperatureBuckets.size shouldBe 5
                response.temperatureBuckets.first().fromC shouldBe null
                response.temperatureBuckets.first().driveCount shouldBe 0
                response.distanceBuckets.size shouldBe 5
                response.distanceBuckets.last().toKm shouldBe null
            }
        }

        `when`("지오펜스도 주소도 없으면") {
            then("places가 null이 아니라 빈 배열이다") {
                stubEmpty()
                service.insights(12).places shouldBe emptyList()
            }
        }
    }

    given("insights — 기존 계약을 그대로 싣는다") {
        `when`("주행 통계가 오면") {
            then("이름을 바꾸지 않고 그대로 낸다") {
                stubEmpty()
                every { vehicleRepository.driveStats() } returns
                    DriveStatsRow(maxSpeedKmh = 138, totalDistanceKm = BigDecimal("107258.4"), recordedMonths = 59)

                val response = service.insights(12)

                response.maxSpeedKmh shouldBe 138
                response.totalDistanceKm shouldBe BigDecimal("107258.4")
                response.recordedMonths shouldBe 59
                response.efficiencyKwhPerKm shouldBe BigDecimal("0.168")
            }
        }
    }
})
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :apps:daily-record:test --tests '*TeslaInsightsServiceTest*'`
Expected: FAIL — `Unresolved reference: TeslaInsightsRepository`

- [ ] **Step 3: 행 타입·리포지토리·SQL을 쓴다**

**`TeslaInsightsRows.kt`는 아직 만들지 않는다** — 이 태스크의 유일한 조회가 `YearMonth`를
그대로 돌려줘 행 타입이 없고, 타입 없는 파일에 import만 두면 ktlint가 잡는다. Task 5에서
첫 행 타입과 함께 만든다.

`TeslaInsightsRepository.kt`:

```kotlin
package com.toy.backend.tesla

import java.time.YearMonth

/**
 * 앱 통계 화면의 집계를 읽는다. **전부 읽기 전용이고 `drives`·`charging_processes`·
 * `addresses`만 본다** — `positions`를 읽는 것은 `/tesla/battery-window` 하나뿐이고 그것도
 * 창이 있다(스펙: 「`positions`를 훑지 않는다」).
 *
 * **범위는 서비스가 UTC로 계산해 넘긴다.** 이 인터페이스에 `months` 같은 파라미터를 두지
 * 않는 이유는 「전체 기간」을 SQL로 표현할 길이 없고, 두면 그 분기가 쿼리 수만큼 복제되기
 * 때문이다.
 *
 * `TeslaVehicleRepository`와 갈라 둔 기준은 답하는 질문이다 — 저쪽은 「차가 어떤 상태인가」,
 * 이쪽은 「지난 N개월을 어떻게 탔나」다.
 */
interface TeslaInsightsRepository {
    /**
     * 가장 오래된 **완료** 주행의 달(KST). 주행이 하나도 없으면 null.
     *
     * `months=0`(전체 기간)의 시작을 정하는 데만 쓴다. `drives` 5천 행 전체 스캔이라
     * 실측 3ms다.
     */
    fun firstDriveMonth(): YearMonth?
}
```

`JdbcTeslaInsightsRepository.kt`:

```kotlin
package com.toy.backend.tesla

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.time.YearMonth

/**
 * **nullable 정수는 `getObject`로 읽는다.** `rs.getInt`는 SQL NULL에 0을 준다 —
 * `JdbcTeslaVehicleRepository`와 같은 규칙이다.
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
    }
}
```

- [ ] **Step 4: 응답 타입을 쓴다**

`TeslaInsightsDtos.kt`:

```kotlin
package com.toy.backend.tesla

import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * 앱 통계 탭 **한 장을 한 응답으로** 채운다. 차트가 스물 몇 장이라 나누면 화면 하나가 열 번
 * 넘게 부르고 그중 아홉은 나머지를 기다린다 — `/tesla/summary`가 목록과 합계를 함께 싣고
 * `/tesla/drive-insights`가 네 카드를 함께 싣는 것과 같은 판단이고, 여기서는 그 이유가 더
 * 세다.
 *
 * **나눗셈은 앱이 한다.** 평균 전비도 요일 평균도 팬텀 드레인율도 분자와 분모를 따로 낸다.
 *
 * `/tesla/drive-insights`가 내던 여덟 필드를 **이름까지 그대로** 싣는다 — 앱이 기존 카드
 * 넷을 옮겨 쓰는 데 매핑 코드가 필요 없게 하려는 것이다.
 */
data class TeslaInsightsResponse(
    /** 받은 범위를 되돌려 싣는다. **0은 전체 기간**이다. */
    val months: Int,
    /** `cars.efficiency` 그대로(kWh/km). null이면 앱이 전비 카드를 감춘다. */
    val efficiencyKwhPerKm: BigDecimal?,
    /** 다섯 개가 늘 온다. 빈 버킷도 자리를 지킨다. */
    val temperatureBuckets: List<TemperatureBucket>,
    /** **0인 칸은 빠진다.** `weekday`는 0이 일요일이다(PostgreSQL `dow` 그대로). */
    val driveTimes: List<DriveTime>,
    /** 다섯 개가 늘 온다. */
    val distanceBuckets: List<DistanceBucket>,
    /** 도착지 상위 10곳. 지오펜스가 없으면 주소로 떨어진다. 없으면 빈 배열이다. */
    val places: List<DrivePlace>,
    /** 역대 최고 속도(km/h). **`months`를 따르지 않는다** — 범위마다 바뀌면 기록이 아니다. */
    val maxSpeedKmh: Int?,
    /** 전 기간 총 주행거리(km). **`months`를 따르지 않는다.** 0을 낸다, null이 아니다. */
    val totalDistanceKm: BigDecimal,
    /** 주행 기록이 있는 달 수 — 평균의 분모다. **0으로 올 수 있다.** */
    val recordedMonths: Int,
)
```

`TemperatureBucket`·`DriveTime`·`DistanceBucket`·`DrivePlace`는 `TeslaVehicleDtos.kt`의 것을 **그대로 재사용한다.** 같은 값을 두 이름으로 내면 앱이 둘 다 알아야 한다.

- [ ] **Step 5: 서비스를 쓴다**

`TeslaInsightsService.kt`:

```kotlin
package com.toy.backend.tesla

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * **`@Transactional`을 붙이지 않는다.** 기본 트랜잭션 매니저는 daily-record 커넥션의 것이라
 * TeslaMate 쪽 SQL에 아무 효력이 없다. 이 서비스는 읽기만 한다.
 *
 * **리포지토리를 둘 주입받는다.** 온도·시간대·거리·장소·주행통계·전비는 `/tesla/drive-insights`가
 * 이미 내던 것이라 `TeslaVehicleRepository`의 것을 그대로 쓴다 — SQL을 복사하면 지금 맞춰 둔
 * 버킷 경계가 두 곳에 생기고, 옛 엔드포인트를 지울 때 어느 쪽이 원본인지 알 수 없게 된다.
 */
@Service
class TeslaInsightsService(
    private val insightsRepository: TeslaInsightsRepository,
    private val vehicleRepository: TeslaVehicleRepository,
) {
    fun insights(months: Int): TeslaInsightsResponse {
        val window = windowOf(months)
        val temperatures = vehicleRepository.driveTemperatureBuckets(window.startUtc, window.endUtc).associateBy { it.bucket }
        val distances = vehicleRepository.driveDistanceBuckets(window.startUtc, window.endUtc).associateBy { it.bucket }
        val stats = vehicleRepository.driveStats()

        return TeslaInsightsResponse(
            months = months,
            efficiencyKwhPerKm = vehicleRepository.carEfficiency(),
            temperatureBuckets =
                TeslaBuckets.TEMPERATURE.map { (bucket, bounds) ->
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
                vehicleRepository.driveTimes(window.startUtc, window.endUtc).map {
                    DriveTime(weekday = it.weekday, hour = it.hour, count = it.count)
                },
            distanceBuckets =
                TeslaBuckets.DISTANCE.map { (bucket, bounds) ->
                    val row = distances[bucket]
                    DistanceBucket(
                        fromKm = bounds.first,
                        toKm = bounds.second,
                        driveCount = row?.driveCount ?: 0,
                        distanceKm = row?.distanceKm ?: BigDecimal.ZERO,
                    )
                },
            places =
                vehicleRepository.drivePlaces(window.startUtc, window.endUtc).map {
                    DrivePlace(name = it.name, driveCount = it.driveCount, distanceKm = it.distanceKm)
                },
            maxSpeedKmh = stats.maxSpeedKmh,
            totalDistanceKm = stats.totalDistanceKm,
            recordedMonths = stats.recordedMonths,
        )
    }

    /**
     * 조회 범위를 한 번만 정하고 모든 쿼리가 그것을 쓴다. **달에 맞춘 창이다** —
     * `monthly` 배열이 달 단위라, 한 응답 안에서 「12개월」의 뜻이 배열마다 다르면 안 된다.
     * (`/tesla/drive-insights`는 구르는 창을 쓴다. 그쪽 뜻은 바꾸지 않았다.)
     *
     * **끝은 요청 시각이다.** 달 끝으로 잡으면 진행 중인 달의 정지 시간에 아직 오지 않은
     * 날들이 들어간다.
     *
     * `months=0`이면 가장 오래된 주행의 달부터다. 주행이 하나도 없으면 이번 달 한 칸만 남는다.
     */
    private fun windowOf(months: Int): InsightsWindow {
        if (months !in ALL_MONTHS..MAX_MONTHS) {
            throw CustomException(ErrorCode.INVALID_REQUEST, "months는 $ALL_MONTHS~$MAX_MONTHS 사이여야 합니다(0은 전체 기간)")
        }

        val nowKst = TeslaTime.nowKst()
        val toMonth = YearMonth.from(nowKst)
        val fromMonth =
            if (months == ALL_MONTHS) {
                insightsRepository.firstDriveMonth()?.coerceAtMost(toMonth) ?: toMonth
            } else {
                toMonth.minusMonths((months - 1).toLong())
            }
        val startKst = fromMonth.atDay(1).atStartOfDay()

        return InsightsWindow(
            fromMonth = fromMonth,
            toMonth = toMonth,
            startKst = startKst,
            endKst = nowKst,
            startUtc = TeslaTime.toUtc(startKst),
            endUtc = TeslaTime.toUtc(nowKst),
        )
    }

    /** 한 요청의 조회 범위. KST와 UTC를 함께 든다 — SQL은 UTC를, 뺄셈은 KST를 쓴다. */
    private data class InsightsWindow(
        val fromMonth: YearMonth,
        val toMonth: YearMonth,
        val startKst: LocalDateTime,
        val endKst: LocalDateTime,
        val startUtc: LocalDateTime,
        val endUtc: LocalDateTime,
    )

    companion object {
        /** `months`의 범위. **0은 전체 기간**이고 상한 60은 실측 기록 길이(60개월)에서 왔다. */
        const val ALL_MONTHS = 0
        const val MAX_MONTHS = 60
    }
}
```

- [ ] **Step 6: 컨트롤러를 쓴다**

`TeslaInsightsController.kt`:

```kotlin
package com.toy.backend.tesla

import com.toy.backend.common.response.DataResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 앱 통계 탭이 쓰는 둘을 모아 둔다. 충전(`TeslaChargeController`)·차량
 * (`TeslaVehicleController`)과 가른 기준은 같다 — **읽는 테이블도 갱신 주기도 답하는 질문도
 * 다르다.**
 *
 * 인증은 기존 `SecurityConfig`가 요구한다. `PublicEndpoint`를 두지 않는다 —
 * 요일별 주행 습관은 충전 시각보다 더 직접적으로 생활을 드러낸다.
 */
@Tag(name = "차량 통계", description = "TeslaMate 주행·충전 통계와 배터리 창 API")
@RestController
@RequestMapping("/tesla")
class TeslaInsightsController(
    private val service: TeslaInsightsService,
) {
    /**
     * **차트 스물 몇 장을 한 응답에 싣는다.** 나누면 화면 하나가 열 번 넘게 부르고 그중
     * 아홉은 나머지를 기다린다.
     *
     * `months`는 응답에 되돌려 실어 앱이 무엇을 받았는지 알 수 있게 한다. **0은 전체 기간**이다.
     */
    @GetMapping("/insights")
    @Operation(summary = "주행·충전 통계 — 월별·요일별·시간대·버킷·기록 (0은 전체 기간)")
    fun insights(
        @Parameter(description = "거슬러 볼 개월 수(0=전체, 1~60)", example = DEFAULT_MONTHS)
        @RequestParam(defaultValue = DEFAULT_MONTHS)
        months: Int,
    ): ResponseEntity<DataResponseBody<TeslaInsightsResponse>> = ResponseEntity.ok(DataResponseBody(service.insights(months)))
}

/** 애너테이션 인자라 컴파일 상수여야 해서 문자열이다. */
private const val DEFAULT_MONTHS = "12"
```

- [ ] **Step 7: 통과를 확인한다**

Run: `./gradlew :apps:daily-record:test --tests '*TeslaInsightsServiceTest*'`
Expected: PASS

- [ ] **Step 8: 커밋한다**

```bash
./gradlew spotlessApply
git add apps/daily-record/src/
git commit -m "feat: 통계 응답의 골격과 기존 주행 인사이트를 옮긴다"
```

---

### Task 5: `monthly` — 월별 주행·충전·정지 시간·팬텀 드레인

**Files:**
- Create: `TeslaInsightsRows.kt`
- Modify: `TeslaInsightsRepository.kt`, `JdbcTeslaInsightsRepository.kt`, `TeslaInsightsDtos.kt`, `TeslaInsightsService.kt`, `TeslaInsightsServiceTest.kt`

**Interfaces:**
- Consumes: `TeslaTime.monthElapsedMinutes`, `InsightsWindow`
- Produces:
  - `InsightsDriveMonthRow(month, driveCount, distanceKm, drivingMin, ratedRangeUsedKm)`
  - `InsightsChargeMonthRow(month, chargeCount, energyAddedKwh, energyUsedKwh, cost, chargingMin)`
  - `ParkDrainMonthRow(month, ratedKm, samples)`
  - `TeslaInsightsRepository.driveMonthly(startUtc, endUtcExclusive): List<InsightsDriveMonthRow>`
  - `TeslaInsightsRepository.chargeMonthly(startUtc, endUtcExclusive): List<InsightsChargeMonthRow>`
  - `TeslaInsightsRepository.parkDrainMonthly(startUtc, endUtcExclusive): List<ParkDrainMonthRow>`
  - `TeslaInsightsResponse.monthly: List<InsightsMonth>`

**팬텀 드레인 SQL의 급소 둘** (실측 2026-08-20으로 잡았다):

1. **`LEAD`는 창 밖까지 포함한 전체 주행 위에서 돌아야 한다.** 창 안쪽만 놓고 `LEAD`를 걸면 창 첫 주행의 「직전 주차」가 창 밖 이웃을 못 봐 통째로 빠진다. `WHERE`는 `LEAD` **뒤에** 건다.
2. **충전 겹침 판정에 `c.end_date IS NOT NULL`이 반드시 있어야 한다.** 없이 `COALESCE(c.end_date, p.to_date)`로 열어 두면 2021년에 시작된 마감 안 된 충전 하나가 **그 뒤 모든 주차 구간을 「충전이 낀 구간」으로 만든다** — 실측으로 표본이 4,587건에서 **76건**으로 무너졌다. `/tesla/state-timeline`의 `SEGMENT_NOT_GHOST`가 막는 것과 같은 계열의 유령이다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`TeslaInsightsServiceTest.kt`의 `stubEmpty()`에 세 줄을 더한다:

```kotlin
        every { insightsRepository.driveMonthly(any(), any()) } returns emptyList()
        every { insightsRepository.chargeMonthly(any(), any()) } returns emptyList()
        every { insightsRepository.parkDrainMonthly(any(), any()) } returns emptyList()
```

그리고 `given` 블록을 더한다:

```kotlin
    given("monthly — 달 축") {
        `when`("기록이 없는 달이 섞여 있으면") {
            then("자리를 지키고 값이 null이다") {
                stubEmpty()
                val response = service.insights(3)

                response.monthly.size shouldBe 3
                response.monthly.first().distanceKm shouldBe null
                response.monthly.first().driveCount shouldBe null
                response.monthly.first().chargeCount shouldBe null
            }
        }

        `when`("오래된 것부터 오는지") {
            then("첫 칸이 가장 옛 달이다") {
                stubEmpty()
                val response = service.insights(3)
                response.monthly.map { it.yearMonth } shouldBe response.monthly.map { it.yearMonth }.sorted()
            }
        }

        `when`("팬텀 드레인 표본이 하나도 없으면") {
            then("null이 아니라 0으로 온다 — 앱이 막대를 안 그린다") {
                stubEmpty()
                service.insights(3).monthly.forEach {
                    it.parkDrainSamples shouldBe 0
                    it.parkDrainRatedKm shouldBe BigDecimal.ZERO
                }
            }
        }
    }

    given("monthly — 정지 시간") {
        `when`("주행과 충전이 있으면") {
            then("그 달 경과 분에서 뺀 값이다") {
                stubEmpty()
                val month = YearMonth.from(TeslaTime.nowKst())
                every { insightsRepository.driveMonthly(any(), any()) } returns
                    listOf(InsightsDriveMonthRow(month, 10, BigDecimal("100.0"), 600, BigDecimal("110.0")))
                every { insightsRepository.chargeMonthly(any(), any()) } returns
                    listOf(InsightsChargeMonthRow(month, 2, BigDecimal("50.0"), BigDecimal("53.0"), BigDecimal("12000"), 300))

                val row = service.insights(1).monthly.single()
                val elapsed = TeslaTime.monthElapsedMinutes(month, month.atDay(1).atStartOfDay(), TeslaTime.nowKst())

                row.idleMin shouldBe elapsed - 900
            }
        }

        `when`("주행·충전이 경과 시간보다 길면") {
            then("음수가 아니라 0이다") {
                stubEmpty()
                val month = YearMonth.from(TeslaTime.nowKst())
                every { insightsRepository.driveMonthly(any(), any()) } returns
                    listOf(InsightsDriveMonthRow(month, 10, BigDecimal("100.0"), 9_999_999, BigDecimal("110.0")))

                service.insights(1).monthly.single().idleMin shouldBe 0
            }
        }
    }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :apps:daily-record:test --tests '*TeslaInsightsServiceTest*'`
Expected: FAIL — `Unresolved reference: driveMonthly`

- [ ] **Step 3: 행 타입 파일을 만든다**

`TeslaInsightsRows.kt` 신규:

```kotlin
/*
 * `/tesla/insights`·`/tesla/battery-window`의 **행 타입**들. 리포지토리가 돌려주고 서비스가
 * 받는다.
 *
 * **여기 있는 시각은 전부 UTC다** — TeslaMate가 타임존 없는 `timestamp` 컬럼에 UTC 값을 넣기
 * 때문이다. KST로 되돌리는 것은 서비스의 일이고, KST를 담는 것은 `TeslaInsightsDtos.kt`의
 * 응답 타입이다. `TeslaVehicleRows.kt`와 같은 경계다.
 */

package com.toy.backend.tesla

import java.math.BigDecimal
import java.time.YearMonth

/**
 * 한 달치 주행 집계. **`/tesla/summary`의 `DriveMonthRow`와 모집단·경계·반올림이 같고**
 * `ratedRangeUsedKm` 하나가 더 있다 — 효율 추세의 분모 재료다. 두 응답의 같은 달 숫자가
 * 달라지면 앱의 어느 화면이 맞는지 알 수 없어진다.
 *
 * **행이 온 달만 온다.** 빈 달의 자리를 채우는 것은 서비스가 한다.
 */
data class InsightsDriveMonthRow(
    val month: YearMonth,
    val driveCount: Int,
    val distanceKm: BigDecimal,
    val drivingMin: Int,
    /** `start_rated_range_km − end_rated_range_km`의 합. **음수 주행은 0으로 보고 더한다.** */
    val ratedRangeUsedKm: BigDecimal,
)

/**
 * 한 달치 충전 집계. `/tesla/summary`의 `ChargeMonthRow`에 `chargingMin`이 더 있다 —
 * 정지 시간의 뺄셈에 쓴다.
 *
 * `cost`만 nullable이다. 금액 미입력 충전만 있는 달이면 `SUM`이 null이고, 그때 **0이 아니라
 * null이 사실이다**(「0원 냈다」가 아니라 「얼마인지 모른다」).
 */
data class InsightsChargeMonthRow(
    val month: YearMonth,
    val chargeCount: Int,
    val energyAddedKwh: BigDecimal,
    val energyUsedKwh: BigDecimal,
    val cost: BigDecimal?,
    val chargingMin: Int,
)

/**
 * 한 달치 팬텀 드레인. **연속한 두 주행 사이에 충전이 하나도 없는 구간**만 세고, 그 구간의
 * `이전 주행 end_rated_range_km − 다음 주행 start_rated_range_km`를 더한 것이 `ratedKm`이다.
 *
 * **음수 구간을 0으로 자르지 않는다.** 충전 기록 없이 정격거리가 늘어난 구간이 실측으로
 * 3,960:628 섞여 있다(BMS 재보정, 또는 TeslaMate가 세션으로 못 잡은 충전). 자르면 합이 위로
 * 편향된다 — 월 합은 어차피 전부 양수로 나온다(실측 75~169km).
 *
 * `samples`를 함께 내는 이유: 표본 3건짜리 달과 90건짜리 달이 응답에서 같아 보이면 안 된다.
 */
data class ParkDrainMonthRow(
    val month: YearMonth,
    val ratedKm: BigDecimal,
    val samples: Int,
)
```

- [ ] **Step 4: 리포지토리 메서드 셋과 SQL을 더한다**

`TeslaInsightsRepository.kt`에 더한다:

```kotlin
    /**
     * 월별 주행 집계. **월 경계는 `start_date` 기준 KST**다 — `/tesla/summary`와 같은
     * 모집단·경계를 쓴다. 기록이 없는 달은 행이 오지 않는다.
     */
    fun driveMonthly(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<InsightsDriveMonthRow>

    /** 월별 충전 집계. 경계 규칙은 `driveMonthly`와 같다. */
    fun chargeMonthly(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<InsightsChargeMonthRow>

    /**
     * 월별 팬텀 드레인. **연속한 두 주행 사이에 충전이 없는 구간**만 센다.
     *
     * 구간은 앞 주행이 끝난 달로 친다. 자정을 걸친 주차는 앞 달로 들어가는데, 그 편이
     * 「그 달에 세운 차가 얼마나 샜나」에 가깝다.
     */
    fun parkDrainMonthly(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<ParkDrainMonthRow>
```

`TeslaInsightsRepository.kt` 상단 import에 `java.time.LocalDateTime`을 더한다.

`JdbcTeslaInsightsRepository.kt`에 더한다:

```kotlin
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
                    drivingMin = rs.getInt("driving_min"),
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
```

`companion object`에 더한다:

```kotlin
        /**
         * **`/tesla/summary`의 `DRIVE_MONTHLY_SQL`과 모집단·경계·반올림이 같아야 한다** —
         * 두 화면이 같은 달의 주행거리를 다르게 내면 안 된다. 다른 것은 범위가 파라미터로
         * 온다는 것과 `rated_range_used_km`이 더 있다는 것뿐이다.
         *
         * **`GREATEST(..., 0)`으로 음수 주행을 0으로 본다.** 정격거리가 늘어난 채 끝난 주행이
         * 있는데(회생·BMS 재보정), 효율 추세의 분모라 음수가 섞이면 그 달만 전비가 튄다.
         * 팬텀 드레인은 반대로 자르지 않는다 — 거기서는 음수가 측정 대상의 일부다.
         *
         * `distance`는 `double precision`이라 `::numeric`으로 올려 반올림한다.
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
         * **`/tesla/summary`의 `CHARGE_MONTHLY_SQL`과 같은 모집단이다.** 축퇴 세션
         * (`charge_energy_added = 0`이고 금액도 없는 것)을 여기서 걸러 내지 않는 것도 그대로다 —
         * 걸러 내면 같은 달의 충전 건수가 두 화면에서 달라진다.
         *
         * `cost`에 `SUM`을 그냥 건다 — null을 건너뛰므로 **실제로 낸 돈**이 된다.
         * `duration_min`은 `COALESCE`로 0을 채운다: 정지 시간의 뺄셈에서 null이 전체를
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
         * 월별 팬텀 드레인. **연속한 두 주행 사이에 충전이 하나도 없는 구간**의 정격거리
         * 하락을 더한다.
         *
         * **급소 1 — `LEAD`를 창 밖까지 포함해 돌린다.** CTE에 범위 조건을 넣고 `LEAD`를
         * 걸면 창 첫 주행의 직전 주차가 이웃을 못 봐 통째로 빠진다. 범위는 `LEAD` **뒤**의
         * `WHERE`에서 건다. `drives`가 5천 행이라 전체를 훑어도 실측 20ms다.
         *
         * **급소 2 — 겹침 판정에 `c.end_date IS NOT NULL`이 반드시 있어야 한다.** 마감되지
         * 않은 충전(이 DB에 2021~2025년 6건)을 `COALESCE`로 「지금까지 열려 있음」으로 보면
         * 그 뒤 **모든** 주차 구간이 「충전이 낀 구간」이 된다 — 실측으로 표본이 4,587건에서
         * 76건으로 무너졌다. `/tesla/state-timeline`의 `SEGMENT_NOT_GHOST`와 같은 계열이다.
         *
         * **`BETWEEN`이 아니라 겹침으로 본다.** 주차 시작 전에 시작해 주차 중에 끝난 충전을
         * `BETWEEN c.start_date`로는 못 잡는다. 실측 차이는 전 기간 1건(4,588 → 4,587)이지만,
         * 이 판정이 「순수 주차인가」의 정의 자체라 정확한 쪽을 쓴다.
         *
         * **음수를 자르지 않는다.** 근거는 `ParkDrainMonthRow`의 KDoc.
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
```

- [ ] **Step 5: 응답 타입을 더한다**

`TeslaInsightsDtos.kt`에 `TeslaInsightsResponse`의 `months` 바로 뒤 필드로 더하고, 타입을 새로 쓴다:

```kotlin
    /**
     * 창의 오래된 달부터 이번 달까지, **기록이 없는 달도 자리를 지킨다**(0과 null은 다르다).
     *
     * `/tesla/summary`의 `trend`와 겹치지만 없애지 않는다 — `trend`는 12개월 고정이고
     * 이쪽은 기간 칩을 따른다. 앱은 개요·충전 탭에서 `trend`를, 통계 탭에서 이것을 본다.
     */
    val monthly: List<InsightsMonth>,
```

```kotlin
/**
 * 한 달치 통계. **기록이 없는 필드는 0이 아니라 null이다** — 0은 「안 탔다」는 뜻이 되어
 * 「기록이 없다」와 구분되지 않는다(`MonthlyStat`과 같은 규칙이다).
 *
 * **예외가 셋 있다.** `idleMin`·`parkDrainRatedKm`·`parkDrainSamples`는 기록이 없어도
 * 값이 온다 — 정지 시간은 「그 달에 아무 기록이 없다」가 곧 「내내 서 있었다」이고,
 * 팬텀 드레인은 표본 수를 함께 내므로 0이 「표본이 없다」를 이미 말한다.
 */
data class InsightsMonth(
    val yearMonth: YearMonth,
    val distanceKm: BigDecimal?,
    val driveCount: Int?,
    val drivingMin: Int?,
    val energyAddedKwh: BigDecimal?,
    val energyUsedKwh: BigDecimal?,
    val cost: BigDecimal?,
    val chargeCount: Int?,
    val chargingMin: Int?,
    /** 효율 추세의 분모 재료. kWh 환산(`× efficiencyKwhPerKm`)과 나눗셈은 앱이 한다. */
    val ratedRangeUsedKm: BigDecimal?,
    /**
     * 정지 시간(분) = **그 달의 창 안 경과 분 − 주행 분 − 충전 분**. 0 미만은 0으로 자른다.
     *
     * `states`를 읽지 않는다. 이 차량의 `states`는 신뢰가 낮고(최근 7일 `offline` 131시간,
     * `asleep` 0개) 오프라인이 곧 주차도 아니다. 빼기로 내면 「차가 얼마나 서 있었나」에
     * 정확히 답한다.
     *
     * **진행 중인 달은 지금까지만 센다.** 달 전체로 잡으면 아직 오지 않은 날이 정지 시간에
     * 들어가 그 달만 막대가 솟는다.
     */
    val idleMin: Int,
    /** 주차 구간 정격거리 하락 합(km). **음수 구간도 부호 그대로 들어 있다.** */
    val parkDrainRatedKm: BigDecimal,
    /** 위 합이 몇 구간에서 나왔나. **0이면 앱이 막대를 안 그린다.** */
    val parkDrainSamples: Int,
)
```

- [ ] **Step 6: 서비스에서 달 축을 만든다**

`TeslaInsightsService.insights` 안, `stats` 아래에 더한다:

```kotlin
        val drives = insightsRepository.driveMonthly(window.startUtc, window.endUtc).associateBy { it.month }
        val charges = insightsRepository.chargeMonthly(window.startUtc, window.endUtc).associateBy { it.month }
        val parkDrains = insightsRepository.parkDrainMonthly(window.startUtc, window.endUtc).associateBy { it.month }
```

응답에 `monthly = window.months().map { monthOf(it, window, drives, charges, parkDrains) }`를 싣고, 아래 둘을 더한다:

```kotlin
    /** 창의 달을 오래된 것부터. **기록이 없는 달도 자리를 지킨다.** */
    private fun InsightsWindow.months(): List<YearMonth> =
        generateSequence(fromMonth) { it.plusMonths(1) }
            .takeWhile { !it.isAfter(toMonth) }
            .toList()

    /**
     * 행 셋을 한 달로 합친다. **서비스가 하는 유일한 산술이 여기 있다** — 정지 시간의 뺄셈이다.
     * 그것을 SQL로 옮기려면 「지금」을 SQL이 알아야 하고, 그러면 테스트가 시각을 못 박을 수 없다.
     */
    private fun monthOf(
        month: YearMonth,
        window: InsightsWindow,
        drives: Map<YearMonth, InsightsDriveMonthRow>,
        charges: Map<YearMonth, InsightsChargeMonthRow>,
        parkDrains: Map<YearMonth, ParkDrainMonthRow>,
    ): InsightsMonth {
        val drive = drives[month]
        val charge = charges[month]
        val parkDrain = parkDrains[month]
        val elapsedMin = TeslaTime.monthElapsedMinutes(month, window.startKst, window.endKst)

        return InsightsMonth(
            yearMonth = month,
            distanceKm = drive?.distanceKm,
            driveCount = drive?.driveCount,
            drivingMin = drive?.drivingMin,
            energyAddedKwh = charge?.energyAddedKwh,
            energyUsedKwh = charge?.energyUsedKwh,
            cost = charge?.cost,
            chargeCount = charge?.chargeCount,
            chargingMin = charge?.chargingMin,
            ratedRangeUsedKm = drive?.ratedRangeUsedKm,
            idleMin = (elapsedMin - (drive?.drivingMin ?: 0) - (charge?.chargingMin ?: 0)).coerceAtLeast(0),
            parkDrainRatedKm = parkDrain?.ratedKm ?: BigDecimal.ZERO,
            parkDrainSamples = parkDrain?.samples ?: 0,
        )
    }
```

- [ ] **Step 7: 통과를 확인한다**

Run: `./gradlew :apps:daily-record:test --tests '*TeslaInsightsServiceTest*'`
Expected: PASS

- [ ] **Step 8: 커밋한다**

```bash
./gradlew spotlessApply
git add apps/daily-record/src/
git commit -m "feat: 월별 주행·충전·정지 시간·팬텀 드레인을 낸다"
```

---

### Task 6: `weekday` — 요일별 주행과 정지 시간

**Files:**
- Modify: `TeslaInsightsRows.kt`, `TeslaInsightsRepository.kt`, `JdbcTeslaInsightsRepository.kt`, `TeslaInsightsDtos.kt`, `TeslaInsightsService.kt`, `TeslaInsightsServiceTest.kt`

**Interfaces:**
- Consumes: `TeslaTime.weekdaySpans`, `InsightsWindow`
- Produces:
  - `WeekdayDriveRow(weekday, driveCount, distanceKm, drivingMin)` — `weekday`는 **1=월요일**
  - `WeekdayChargeRow(weekday, chargingMin)`
  - `TeslaInsightsRepository.weekdayDrives(startUtc, endUtcExclusive): List<WeekdayDriveRow>`
  - `TeslaInsightsRepository.weekdayCharges(startUtc, endUtcExclusive): List<WeekdayChargeRow>`
  - `TeslaInsightsResponse.weekday: List<InsightsWeekday>`

**스펙 JSON보다 `drivingMin` 하나를 더 낸다.** 스펙의 `weekday` 예시에는 없지만 `monthly`에는
있고, 없으면 「요일별 평균 주행 시간」을 앱이 만들 수 없다. 정지 시간의 뺄셈에 어차피 필요한
값이라 재료가 이미 손에 있다.

**요일 번호가 두 벌인 것을 여기서 못 박는다.** `weekday[]`는 **ISO(1=월요일)**이고 `driveTimes`·`chargeTimes`는 **PostgreSQL `dow`(0=일요일)**다. 섞이지 않는 이유는 앞의 것이 「요일별 막대」이고 뒤의 것이 「요일×시각 히트맵」이라 화면이 다르기 때문이고, 뒤의 둘은 기존 계약(`DriveTime`)을 그대로 물려받아야 해서 바꿀 수 없다. **양쪽 KDoc에 반드시 적는다.**

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`stubEmpty()`에 두 줄을 더한다:

```kotlin
        every { insightsRepository.weekdayDrives(any(), any()) } returns emptyList()
        every { insightsRepository.weekdayCharges(any(), any()) } returns emptyList()
```

`given` 블록을 더한다:

```kotlin
    given("weekday — 요일 축") {
        `when`("행이 하나도 없으면") {
            then("일곱 요일이 1(월)부터 자리를 지킨다") {
                stubEmpty()
                val weekday = service.insights(12).weekday

                weekday.size shouldBe 7
                weekday.map { it.weekday } shouldBe (1..7).toList()
                weekday.first().driveCount shouldBe 0
            }
        }

        `when`("창이 정확히 4주면") {
            then("occurrences가 요일마다 4다") {
                stubEmpty()
                // 창 시작이 달 1일이라 정확한 주 수를 못 박을 수 없다.
                // 대신 합이 창의 날 수와 같은지 본다 — 어느 날도 두 요일에 세어지지 않는다.
                val weekday = service.insights(3).weekday
                weekday.sumOf { it.occurrences } shouldBe
                    TeslaTime
                        .weekdaySpans(
                            YearMonth.from(TeslaTime.nowKst()).minusMonths(2).atDay(1).atStartOfDay(),
                            TeslaTime.nowKst(),
                        ).values
                        .sumOf { it.occurrences }
            }
        }

        `when`("주행·충전이 있으면") {
            then("idleMin이 그 요일 경과 분에서 뺀 값이다") {
                stubEmpty()
                every { insightsRepository.weekdayDrives(any(), any()) } returns
                    listOf(WeekdayDriveRow(1, 5, BigDecimal("80.0"), 200))
                every { insightsRepository.weekdayCharges(any(), any()) } returns
                    listOf(WeekdayChargeRow(1, 100))

                val monday = service.insights(1).weekday.single { it.weekday == 1 }
                val span =
                    TeslaTime
                        .weekdaySpans(YearMonth.from(TeslaTime.nowKst()).atDay(1).atStartOfDay(), TeslaTime.nowKst())[1]!!

                monday.idleMin shouldBe (span.elapsedMin - 300).coerceAtLeast(0)
                monday.occurrences shouldBe span.occurrences
            }
        }
    }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :apps:daily-record:test --tests '*TeslaInsightsServiceTest*'`
Expected: FAIL — `Unresolved reference: weekdayDrives`

- [ ] **Step 3: 행 타입·리포지토리·SQL을 더한다**

`TeslaInsightsRows.kt`:

```kotlin
/**
 * 요일 하나의 주행 합. **`weekday`는 1이 월요일**(ISO)이고, `DriveTimeRow`의 0=일요일과
 * 다르다 — 저쪽은 기존 계약이라 바꿀 수 없고 이쪽은 앱의 요일 막대가 월요일부터 시작한다.
 * **두 배열이 한 응답에 함께 나가므로 이 차이를 응답 타입 쪽에도 적어 둔다.**
 */
data class WeekdayDriveRow(
    val weekday: Int,
    val driveCount: Int,
    val distanceKm: BigDecimal,
    val drivingMin: Int,
)

/** 요일 하나의 충전 시간. 정지 시간의 뺄셈에만 쓴다 — 그래서 건수·전력량이 없다. */
data class WeekdayChargeRow(
    val weekday: Int,
    val chargingMin: Int,
)
```

`TeslaInsightsRepository.kt`:

```kotlin
    /**
     * 요일별 주행 합. **`weekday`는 1이 월요일**(ISO)이고 **KST로 옮긴 뒤 뽑는다** —
     * UTC로 뽑으면 월요일 아침 출근이 일요일 밤으로 찍힌다. 행이 온 요일만 온다.
     */
    fun weekdayDrives(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<WeekdayDriveRow>

    /** 요일별 충전 시간. 규칙은 `weekdayDrives`와 같다. */
    fun weekdayCharges(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<WeekdayChargeRow>
```

`JdbcTeslaInsightsRepository.kt`:

```kotlin
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
                    drivingMin = rs.getInt("driving_min"),
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
```

```kotlin
        /**
         * **`isodow`라 1이 월요일이다.** 같은 응답의 `driveTimes`·`chargeTimes`는 `dow`(0=일)를
         * 쓴다 — 기존 계약이라 바꿀 수 없다. 한 응답에 두 벌이 나가는 셈이라 응답 타입 쪽에도
         * 적어 두었다.
         *
         * **KST로 옮긴 뒤 뽑는다.** UTC로 뽑으면 월요일 아침 출근이 일요일 밤으로 찍힌다.
         *
         * 경계 컬럼이 `start_date`인 것은 월별 집계와 맞춘 것이다 — 자정을 걸친 주행은 출발한
         * 요일로 친다.
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

        private const val WEEKDAY_CHARGES_SQL = """
            SELECT EXTRACT(isodow FROM cp.start_date AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Seoul')::int AS weekday,
                   COALESCE(SUM(cp.duration_min), 0)::int                                               AS charging_min
              FROM charging_processes cp
             WHERE cp.end_date IS NOT NULL
               AND cp.start_date >= :start
               AND cp.start_date <  :end
             GROUP BY weekday
             ORDER BY weekday
        """
```

- [ ] **Step 4: 응답 타입과 서비스를 더한다**

`TeslaInsightsDtos.kt` — `TeslaInsightsResponse`에 필드를 더한다:

```kotlin
    /**
     * 요일별 합, **월요일(1)부터 일곱 개가 늘 온다.**
     *
     * **`weekday`가 1=월요일이다 — 같은 응답의 `driveTimes`·`chargeTimes`는 0=일요일이다.**
     * 뒤의 둘은 `/tesla/drive-insights`의 기존 계약(PostgreSQL `dow`)을 그대로 물려받았고,
     * 이쪽은 앱의 요일 막대가 월요일부터 시작한다. 섞어 읽으면 하루가 밀린다.
     */
    val weekday: List<InsightsWeekday>,
```

```kotlin
/**
 * 요일 하나의 합. **평균을 내지 않는다 — 분자(`driveCount`·`distanceKm`)와 분모
 * (`occurrences`)를 함께 낸다.** 이 저장소는 나눗셈을 앱에 맡긴다.
 */
data class InsightsWeekday(
    /** **1 = 월요일**(ISO). `driveTimes`의 0=일요일과 다르다. */
    val weekday: Int,
    val driveCount: Int,
    val distanceKm: BigDecimal,
    val drivingMin: Int,
    /**
     * 창 안에서 그 요일이 며칠 나왔나 — **요일 평균의 분모다.**
     *
     * **오늘도 한 번으로 센다.** 오늘의 주행이 이미 분자에 들어 있어 짝이 맞는다.
     * 정지 시간 쪽은 반대로 오늘의 흐른 시간만 센다(아래).
     */
    val occurrences: Int,
    /**
     * 정지 시간(분) = 그 요일에 **실제로 흐른 분** − 주행 − 충전. 0 미만은 0으로 자른다.
     *
     * `occurrences × 1440`이 아니다 — 오늘이 반쪽이라 그렇게 하면 오늘 요일만 정지 시간이
     * 최대 하루치 부풀어 오른다.
     */
    val idleMin: Int,
)
```

`TeslaInsightsService.insights`에 더한다:

```kotlin
        val weekdayDrives = insightsRepository.weekdayDrives(window.startUtc, window.endUtc).associateBy { it.weekday }
        val weekdayCharges = insightsRepository.weekdayCharges(window.startUtc, window.endUtc).associateBy { it.weekday }
        val spans = TeslaTime.weekdaySpans(window.startKst, window.endKst)
```

응답에 싣는다:

```kotlin
            weekday =
                (1..7).map { weekday ->
                    val drive = weekdayDrives[weekday]
                    val charge = weekdayCharges[weekday]
                    val span = spans.getValue(weekday)
                    InsightsWeekday(
                        weekday = weekday,
                        driveCount = drive?.driveCount ?: 0,
                        distanceKm = drive?.distanceKm ?: BigDecimal.ZERO,
                        drivingMin = drive?.drivingMin ?: 0,
                        occurrences = span.occurrences,
                        idleMin = (span.elapsedMin - (drive?.drivingMin ?: 0) - (charge?.chargingMin ?: 0)).coerceAtLeast(0),
                    )
                },
```

- [ ] **Step 5: 통과를 확인한다**

Run: `./gradlew :apps:daily-record:test --tests '*TeslaInsightsServiceTest*'`
Expected: PASS

- [ ] **Step 6: 커밋한다**

```bash
./gradlew spotlessApply
git add apps/daily-record/src/
git commit -m "feat: 요일별 주행과 정지 시간을 낸다"
```

---

### Task 7: `chargeTimes`·`speedBuckets`·`speedEnergyBuckets`

**Files:**
- Modify: `TeslaInsightsRows.kt`, `TeslaInsightsRepository.kt`, `JdbcTeslaInsightsRepository.kt`, `TeslaInsightsDtos.kt`, `TeslaInsightsService.kt`, `TeslaInsightsServiceTest.kt`

**Interfaces:**
- Consumes: `TeslaBuckets.SPEED`·`SPEED_ENERGY`
- Produces:
  - `ChargeTimeRow(weekday, hour, count)` — `weekday`는 **0=일요일**(`DriveTimeRow`와 같다)
  - `SpeedBucketRow(bucket, driveCount)`
  - `SpeedEnergyBucketRow(bucket, distanceKm, ratedRangeUsedKm)`
  - `TeslaInsightsRepository.chargeTimes(startUtc, endUtcExclusive): List<ChargeTimeRow>`
  - `TeslaInsightsRepository.speedBuckets(startUtc, endUtcExclusive): List<SpeedBucketRow>`
  - `TeslaInsightsRepository.speedEnergyBuckets(startUtc, endUtcExclusive): List<SpeedEnergyBucketRow>`
  - `TeslaInsightsResponse.chargeTimes: List<DriveTime>`·`speedBuckets: List<SpeedBucket>`·`speedEnergyBuckets: List<SpeedEnergyBucket>`

**`chargeTimes`가 `DriveTime` 타입을 그대로 쓴다.** 모양이 `weekday`·`hour`·`count`로 같고 요일 규약도 같다. 이름만 다른 쌍둥이 타입을 두면 앱이 둘 다 알아야 한다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`stubEmpty()`에 세 줄을 더한다:

```kotlin
        every { insightsRepository.chargeTimes(any(), any()) } returns emptyList()
        every { insightsRepository.speedBuckets(any(), any()) } returns emptyList()
        every { insightsRepository.speedEnergyBuckets(any(), any()) } returns emptyList()
```

```kotlin
    given("속도 버킷") {
        `when`("행이 하나도 없으면") {
            then("최고속도 일곱 칸·평균속도 다섯 칸이 자리를 지킨다") {
                stubEmpty()
                val response = service.insights(12)

                response.speedBuckets.size shouldBe 7
                response.speedBuckets.last().fromKmh shouldBe 120
                response.speedBuckets.last().toKmh shouldBe null
                response.speedEnergyBuckets.size shouldBe 5
                response.speedEnergyBuckets.last().fromKmh shouldBe 80
                response.speedEnergyBuckets.last().toKmh shouldBe null
            }
        }

        `when`("행이 오면") {
            then("그 칸에 값이 들어간다") {
                stubEmpty()
                every { insightsRepository.speedBuckets(any(), any()) } returns listOf(SpeedBucketRow(6, 372))

                service.insights(12).speedBuckets.single { it.fromKmh == 100 }.driveCount shouldBe 372
            }
        }
    }

    given("chargeTimes") {
        `when`("0인 칸이 있으면") {
            then("행이 온 칸만 낸다 — 168칸을 채우지 않는다") {
                stubEmpty()
                every { insightsRepository.chargeTimes(any(), any()) } returns listOf(ChargeTimeRow(6, 22, 10))

                val times = service.insights(12).chargeTimes
                times.size shouldBe 1
                times.single().weekday shouldBe 6
                times.single().hour shouldBe 22
                times.single().count shouldBe 10
            }
        }
    }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :apps:daily-record:test --tests '*TeslaInsightsServiceTest*'`
Expected: FAIL — `Unresolved reference: chargeTimes`

- [ ] **Step 3: 행 타입을 더한다**

`TeslaInsightsRows.kt`:

```kotlin
/**
 * 요일·시각별 충전 시작 건수. **`weekday`는 0이 일요일**(PostgreSQL `dow`)이다 —
 * `DriveTimeRow`와 같은 규약이고, 같은 히트맵 짝이라 맞춰야 한다.
 * (`WeekdayDriveRow`의 1=월요일과는 다르다.)
 */
data class ChargeTimeRow(
    val weekday: Int,
    val hour: Int,
    val count: Int,
)

/** 최고 속도 버킷 하나의 건수. `bucket`은 1..7이고 경계는 `TeslaBuckets.SPEED`가 갖는다. */
data class SpeedBucketRow(
    val bucket: Int,
    val driveCount: Int,
)

/**
 * 평균 속도 버킷 하나의 합. `bucket`은 1..5이고 경계는 `TeslaBuckets.SPEED_ENERGY`가 갖는다.
 *
 * **건수를 내지 않는다.** 이 배열이 답하는 것은 「빠르게 달릴수록 전비가 어떻게 되나」이고,
 * 그 답은 거리와 정격거리 소모 둘로 난다. 건수는 `speedBuckets`가 이미 낸다.
 */
data class SpeedEnergyBucketRow(
    val bucket: Int,
    val distanceKm: BigDecimal,
    val ratedRangeUsedKm: BigDecimal,
)
```

- [ ] **Step 4: 리포지토리와 SQL을 더한다**

`TeslaInsightsRepository.kt`:

```kotlin
    /**
     * 요일·시각별 충전 시작 건수. **KST로 옮긴 뒤 뽑고 `weekday`는 0이 일요일**이다 —
     * `TeslaVehicleRepository.driveTimes`와 같은 규약이다. 0인 칸은 행이 오지 않는다.
     */
    fun chargeTimes(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<ChargeTimeRow>

    /** 최고 속도 버킷별 주행 건수. 행이 온 버킷만 온다. */
    fun speedBuckets(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<SpeedBucketRow>

    /**
     * 평균 속도 버킷별 거리·정격거리 소모.
     *
     * **평균 속도는 SQL이 주행마다 나눠서 구한다**(`distance ÷ (duration_min ÷ 60)`).
     * 이 나눗셈은 **버킷을 고르기 위한 것이고 응답에 나가지 않으므로** 「나눗셈을 하지
     * 않는다」와 부딪히지 않는다.
     */
    fun speedEnergyBuckets(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<SpeedEnergyBucketRow>
```

`JdbcTeslaInsightsRepository.kt` — 매핑 셋:

```kotlin
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
```

```kotlin
        /** 규약은 `JdbcTeslaVehicleRepository.DRIVE_TIMES_SQL`과 같다 — `dow`라 0이 일요일이다. */
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
         * **버킷 경계는 `TeslaBuckets.SPEED`와 같은 숫자여야 한다.**
         *
         * `ELSE 7`이 「120 이상」을 받는다. 실측(2026-08-20)으로 `speed_max`가 140 이상인
         * 주행이 0건이라 마지막 칸이 사실상 `120~140`이다. **`speed_max IS NOT NULL`을
         * 명시로 건다** — 실측 NULL 0건이지만, 없으면 상류가 값을 못 채우게 되는 날
         * NULL이 조용히 `ELSE 7`(최고 속도 칸)로 들어간다.
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
            WITH d AS (
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
            SELECT CASE WHEN d.avg_speed < 20 THEN 1
                        WHEN d.avg_speed < 40 THEN 2
                        WHEN d.avg_speed < 60 THEN 3
                        WHEN d.avg_speed < 80 THEN 4
                        ELSE 5
                   END                                AS bucket,
                   ROUND(SUM(d.distance)::numeric, 1) AS distance_km,
                   ROUND(SUM(d.rated_used), 1)        AS rated_range_used_km
              FROM d
             GROUP BY bucket
             ORDER BY bucket
        """
```

- [ ] **Step 5: 응답 타입과 서비스를 더한다**

`TeslaInsightsDtos.kt` — `TeslaInsightsResponse`에:

```kotlin
    /** 충전 시작의 요일×시각. **0인 칸은 빠지고 `weekday`는 0이 일요일**이다(`driveTimes`와 같다). */
    val chargeTimes: List<DriveTime>,
    /** 일곱 개가 늘 온다. 주행 한 건의 **최고** 속도 분포다. */
    val speedBuckets: List<SpeedBucket>,
    /** 다섯 개가 늘 온다. 주행 한 건의 **평균** 속도별 전비 재료다. */
    val speedEnergyBuckets: List<SpeedEnergyBucket>,
```

```kotlin
/** `toKmh`가 null이면 상한이 없다. 경계는 **`fromKmh` 포함, `toKmh` 미만**이다. */
data class SpeedBucket(
    val fromKmh: Int,
    val toKmh: Int?,
    val driveCount: Int,
)

/**
 * 평균 속도별 거리와 정격거리 소모. **전비는 앱이 낸다** —
 * `distanceKm ÷ (ratedRangeUsedKm × efficiencyKwhPerKm)`이고, 분모가 0인 칸의 처리는
 * 화면이 정한다.
 *
 * **`driveCount`가 없다.** 건수는 `speedBuckets`가 낸다. 다만 두 배열의 모집단이 다르다 —
 * 이쪽은 `ΔratedRange > 0`인 주행만 든다(전비가 무한대가 되는 주행을 뺀다).
 */
data class SpeedEnergyBucket(
    val fromKmh: Int,
    val toKmh: Int?,
    val distanceKm: BigDecimal,
    val ratedRangeUsedKm: BigDecimal,
)
```

`TeslaInsightsService.insights`에 조회 셋을 더하고 응답에 싣는다:

```kotlin
        val speeds = insightsRepository.speedBuckets(window.startUtc, window.endUtc).associateBy { it.bucket }
        val speedEnergies = insightsRepository.speedEnergyBuckets(window.startUtc, window.endUtc).associateBy { it.bucket }
```

```kotlin
            chargeTimes =
                insightsRepository.chargeTimes(window.startUtc, window.endUtc).map {
                    DriveTime(weekday = it.weekday, hour = it.hour, count = it.count)
                },
            speedBuckets =
                TeslaBuckets.SPEED.map { (bucket, bounds) ->
                    SpeedBucket(
                        fromKmh = bounds.first,
                        toKmh = bounds.second,
                        driveCount = speeds[bucket]?.driveCount ?: 0,
                    )
                },
            speedEnergyBuckets =
                TeslaBuckets.SPEED_ENERGY.map { (bucket, bounds) ->
                    val row = speedEnergies[bucket]
                    SpeedEnergyBucket(
                        fromKmh = bounds.first,
                        toKmh = bounds.second,
                        distanceKm = row?.distanceKm ?: BigDecimal.ZERO,
                        ratedRangeUsedKm = row?.ratedRangeUsedKm ?: BigDecimal.ZERO,
                    )
                },
```

- [ ] **Step 6: 통과를 확인하고 커밋한다**

Run: `./gradlew :apps:daily-record:test --tests '*TeslaInsightsServiceTest*'`
Expected: PASS

```bash
./gradlew spotlessApply
git add apps/daily-record/src/
git commit -m "feat: 충전 시간대와 속도 버킷 둘을 낸다"
```

---

### Task 8: `chargeStartLevels`·`chargeEndLevels`·`chargers`·`regions`

**Files:**
- Modify: `TeslaInsightsRows.kt`, `TeslaInsightsRepository.kt`, `JdbcTeslaInsightsRepository.kt`, `TeslaInsightsDtos.kt`, `TeslaInsightsService.kt`, `TeslaInsightsServiceTest.kt`

**Interfaces:**
- Consumes: `TeslaBuckets.CHARGE_LEVEL`
- Produces:
  - `ChargeLevelBucketRow(bucket, startCount, endCount)` — 한 쿼리가 둘을 함께 낸다
  - `ChargerRow(name, chargeCount, energyAddedKwh, cost, costMissingCount)`
  - `RegionRow(cities, states, countries)`
  - `TeslaInsightsRepository.chargeLevelBuckets(...)`·`chargers(...)`·`regions(...)`
  - `TeslaInsightsResponse.chargeStartLevels`·`chargeEndLevels`·`chargers`·`regions`

**SoC 버킷의 급소.** `level / 10 + 1`로 칸을 고르면 **정확히 100%가 11번 칸**이 되어 어느 라벨에도 안 붙는다. 실측으로 그런 충전이 71건, 즉 **가장 흔한 종료 SoC가 통째로 사라진다.** `LEAST(level / 10, 9) + 1`로 마지막 칸에 넣는다 — `TeslaBuckets.CHARGE_LEVEL`의 마지막 칸만 양끝이 닫히는 이유가 이것이다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`stubEmpty()`에:

```kotlin
        every { insightsRepository.chargeLevelBuckets(any(), any()) } returns emptyList()
        every { insightsRepository.chargers(any(), any()) } returns emptyList()
        every { insightsRepository.regions(any(), any()) } returns RegionRow(0, 0, 0)
```

```kotlin
    given("충전 SoC 버킷") {
        `when`("행이 하나도 없으면") {
            then("시작·종료 각각 열 칸이 자리를 지킨다") {
                stubEmpty()
                val response = service.insights(12)

                response.chargeStartLevels.size shouldBe 10
                response.chargeEndLevels.size shouldBe 10
                response.chargeEndLevels.last().fromPct shouldBe 90
                response.chargeEndLevels.last().toPct shouldBe 100
            }
        }

        `when`("100%로 끝난 충전이 오면") {
            then("마지막 칸에 든다 — 열한 번째 칸이 생기지 않는다") {
                stubEmpty()
                every { insightsRepository.chargeLevelBuckets(any(), any()) } returns
                    listOf(ChargeLevelBucketRow(bucket = 10, startCount = 4, endCount = 71))

                val response = service.insights(12)
                response.chargeEndLevels.size shouldBe 10
                response.chargeEndLevels.last().count shouldBe 71
                response.chargeStartLevels.last().count shouldBe 4
            }
        }
    }

    given("chargers·regions") {
        `when`("지오펜스가 0행이면") {
            then("chargers가 null이 아니라 빈 배열이다") {
                stubEmpty()
                service.insights(12).chargers shouldBe emptyList()
            }
        }

        `when`("금액 미입력이 섞여 있으면") {
            then("그 개수를 함께 낸다") {
                stubEmpty()
                every { insightsRepository.chargers(any(), any()) } returns
                    listOf(ChargerRow("Soraebi-ro", 5, BigDecimal("173.6"), null, 5))

                val charger = service.insights(12).chargers.single()
                charger.cost shouldBe null
                charger.costMissingCount shouldBe 5
            }
        }

        `when`("주소가 하나도 없으면") {
            then("regions가 전부 0이다 — null이 아니다") {
                stubEmpty()
                service.insights(12).regions.cities shouldBe 0
            }
        }
    }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :apps:daily-record:test --tests '*TeslaInsightsServiceTest*'`
Expected: FAIL — `Unresolved reference: chargeLevelBuckets`

- [ ] **Step 3: 행 타입을 더한다**

```kotlin
/**
 * 충전 SoC 버킷 하나. **시작과 종료를 한 행에 함께 낸다** — 같은 모집단 위의 두 분포라
 * 쿼리를 나누면 같은 테이블을 두 번 훑는다. `bucket`은 1..10이고 경계는
 * `TeslaBuckets.CHARGE_LEVEL`이 갖는다.
 *
 * **정확히 100%는 10번 칸이다.** `level / 10 + 1`이면 11번이 되어 어느 라벨에도 안 붙는데,
 * 실측으로 그런 충전이 71건 — 가장 흔한 종료 SoC가 통째로 사라진다.
 */
data class ChargeLevelBucketRow(
    val bucket: Int,
    val startCount: Int,
    val endCount: Int,
)

/**
 * 충전소 하나의 합. 이름은 **지오펜스 → 주소** 순으로 떨어진다(`DRIVE_PLACES_SQL`과 같은
 * COALESCE다). 이 DB는 지오펜스가 0행이지만 주소가 이름을 채워 실측으로 7~12곳이 나온다.
 *
 * `cost`는 nullable이다 — 그 충전소의 충전이 전부 금액 미입력이면 `SUM`이 null이고,
 * 그때 **0이 아니라 null이 사실이다.**
 */
data class ChargerRow(
    val name: String,
    val chargeCount: Int,
    val energyAddedKwh: BigDecimal,
    val cost: BigDecimal?,
    /** 금액 미입력 건수. **이 값이 없으면 「충전소별 비용 TOP」 순위가 조용히 뒤집힌다.** */
    val costMissingCount: Int,
)

/** 다녀온 지역 수. `GROUP BY`가 없어 주소가 하나도 없어도 0이 든 행이 온다. */
data class RegionRow(
    val cities: Int,
    val states: Int,
    val countries: Int,
)
```

- [ ] **Step 4: 리포지토리와 SQL을 더한다**

`TeslaInsightsRepository.kt`:

```kotlin
    /** 충전 시작·종료 SoC 분포. 행이 온 버킷만 온다. */
    fun chargeLevelBuckets(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<ChargeLevelBucketRow>

    /**
     * 충전소별 합, 건수 많은 순 상위 10곳. 이름이 끝내 없는 충전은 세지 않는다.
     *
     * 모집단은 `/tesla/charges/totals`와 같다 — 케이블만 꽂았다 뺀 축퇴 세션을 뺀다.
     */
    fun chargers(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<ChargerRow>

    /** 주행 도착지 기준 지역 수. **행은 늘 온다**(`GROUP BY`가 없다). */
    fun regions(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): RegionRow
```

`JdbcTeslaInsightsRepository.kt`:

```kotlin
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
```

```kotlin
        /**
         * **`LEAST(level / 10, 9) + 1`이 급소다.** `level / 10 + 1`이면 정확히 100%가 11번
         * 칸이 되어 `TeslaBuckets.CHARGE_LEVEL`의 어느 라벨에도 안 붙는다 —
         * 실측(2026-08-20)으로 그런 충전이 71건이고 종료 SoC 중 가장 흔한 값이다.
         *
         * **시작과 종료를 `UNION ALL`로 한 번에 뽑는다.** 같은 모집단 위의 두 분포라
         * 쿼리를 나누면 같은 테이블을 두 번 훑는다.
         *
         * `start_battery_level`·`end_battery_level`이 null인 충전이 실측 1건 있어 각각
         * 자기 쪽에서만 뺀다 — 공통 WHERE로 올리면 한쪽 지표의 조건이 다른 쪽 표본을 깎는다.
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
         * **모집단은 `/tesla/charges/totals`의 `CHARGE_POPULATION`과 같다** — 케이블만 꽂았다
         * 뺀 축퇴 세션을 뺀다. 두 화면의 충전 건수가 서로 말이 되려면 같아야 한다.
         *
         * **COALESCE 순서는 `DRIVE_PLACES_SQL`과 같고 표시 이름으로 묶는다.** 주소는
         * 재지오코딩 결과마다 행이 갈리는데, 사람이 같은 곳으로 읽는 것을 두 줄로 내면
         * 목록이 무너진다. 묶고 나면 이름이 결과 안에서 유일해져 앱의 행 식별도 안정된다.
         *
         * **`cost`에 `SUM`을 그냥 건다** — null을 건너뛰므로 실제로 낸 돈이 된다.
         * 미입력분의 크기는 `cost_missing_count`가 따로 낸다. 이 값이 없으면 「충전소별
         * 비용 TOP」 순위가 조용히 뒤집힌다.
         *
         * `ORDER BY`의 마지막 열이 이름인 것은 `LIMIT 10` 경계의 tie-breaker다.
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
         * 주행의 도착지라 두 번 세게 되기 때문이다.
         *
         * `COUNT(DISTINCT ...)`가 null을 건너뛰므로 주소가 하나도 없으면 셋 다 0이다.
         * `GROUP BY`가 없어 행은 늘 온다.
         *
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
```

- [ ] **Step 5: 응답 타입과 서비스를 더한다**

`TeslaInsightsDtos.kt` — `TeslaInsightsResponse`에:

```kotlin
    /** 열 개가 늘 온다. 충전을 **시작한** SoC 분포다. */
    val chargeStartLevels: List<ChargeLevelBucket>,
    /** 열 개가 늘 온다. 충전을 **끝낸** SoC 분포다. */
    val chargeEndLevels: List<ChargeLevelBucket>,
    /** 충전소 상위 10곳. 지오펜스가 없으면 주소로 떨어진다. 없으면 빈 배열이다. */
    val chargers: List<Charger>,
    /** 다녀온 지역 수. 주소가 없으면 셋 다 0이다 — null이 아니다. */
    val regions: Regions,
```

```kotlin
/**
 * 충전 SoC 버킷. 경계는 **`fromPct` 포함, `toPct` 미만**인데 **마지막 칸(`90~100`)만
 * 양끝이 닫힌다** — 정확히 100%로 끝난 충전이 실측 71건이라, 「미만」으로 두면 가장 흔한
 * 값이 어느 칸에도 안 들어간다.
 */
data class ChargeLevelBucket(
    val fromPct: Int,
    val toPct: Int,
    val count: Int,
)

/**
 * 충전소 하나. **표시 이름으로 묶여 오므로 이 목록 안에서 `name`은 유일하다** —
 * 앱이 이름을 행 식별에 써도 안전하다. 좌표는 내지 않는다.
 */
data class Charger(
    val name: String,
    val chargeCount: Int,
    val energyAddedKwh: BigDecimal,
    /** **실제로 낸 돈이다.** 그 충전소가 전부 금액 미입력이면 null이다 — 0이 아니다. */
    val cost: BigDecimal?,
    /**
     * 금액 미입력 건수. 앱이 「4건 금액 없음」을 적을 수 있어야 한다 —
     * 없으면 「충전소별 비용 TOP」 순위가 조용히 뒤집힌다.
     * `/tesla/charges/totals`가 같은 이유로 같은 필드를 낸다.
     */
    val costMissingCount: Int,
)

/** 주행 도착지 주소 기준 지역 수. 이 차량은 나라가 1이지만 필드를 둔다 — 앱이 감출지 정한다. */
data class Regions(
    val cities: Int,
    val states: Int,
    val countries: Int,
)
```

`TeslaInsightsService.insights`에:

```kotlin
        val chargeLevels = insightsRepository.chargeLevelBuckets(window.startUtc, window.endUtc).associateBy { it.bucket }
```

```kotlin
            chargeStartLevels =
                TeslaBuckets.CHARGE_LEVEL.map { (bucket, bounds) ->
                    ChargeLevelBucket(bounds.first, bounds.second, chargeLevels[bucket]?.startCount ?: 0)
                },
            chargeEndLevels =
                TeslaBuckets.CHARGE_LEVEL.map { (bucket, bounds) ->
                    ChargeLevelBucket(bounds.first, bounds.second, chargeLevels[bucket]?.endCount ?: 0)
                },
            chargers =
                insightsRepository.chargers(window.startUtc, window.endUtc).map {
                    Charger(
                        name = it.name,
                        chargeCount = it.chargeCount,
                        energyAddedKwh = it.energyAddedKwh,
                        cost = it.cost,
                        costMissingCount = it.costMissingCount,
                    )
                },
            regions =
                insightsRepository.regions(window.startUtc, window.endUtc).let {
                    Regions(cities = it.cities, states = it.states, countries = it.countries)
                },
```

- [ ] **Step 6: 통과를 확인하고 커밋한다**

Run: `./gradlew :apps:daily-record:test --tests '*TeslaInsightsServiceTest*'`
Expected: PASS

```bash
./gradlew spotlessApply
git add apps/daily-record/src/
git commit -m "feat: 충전 SoC 분포와 충전소·지역 수를 낸다"
```

---

### Task 9: `records` — 명예의 전당 셋

**Files:**
- Modify: `TeslaInsightsRows.kt`, `TeslaInsightsRepository.kt`, `JdbcTeslaInsightsRepository.kt`, `TeslaInsightsDtos.kt`, `TeslaInsightsService.kt`, `TeslaInsightsServiceTest.kt`

**Interfaces:**
- Consumes: 없음
- Produces:
  - `DriveRecordRow(kind, driveId, startedAtUtc, distanceKm, durationMin, ratedRangeUsedKm)` — `kind`는 `distance`·`duration`·`efficiency`
  - `TeslaInsightsRepository.driveRecords(): List<DriveRecordRow>` — **`kind` 순서로 최대 3행**: 거리·시간·효율
  - `TeslaInsightsResponse.records: InsightsRecords`

**`months`를 따르지 않는다.** 「명예의 전당」은 역대다 — 범위가 바뀔 때마다 1등이 바뀌면 기록이 아니다. 같은 응답의 `maxSpeedKmh`·`totalDistanceKm`·`recordedMonths`가 이미 같은 규약이고, 앱은 이 넷의 라벨을 「역대」로 적어 범위가 다름을 글자로 드러낸다.

**최고 효율에 거리 하한 20km를 건다.** 하한이 없으면 실측으로 **0.2km 주행이 8.2배로 1등**이 된다 — 정격거리 표시가 움직이지 않을 만큼 짧은 주행이다. 20km면 1등이 26.7km/15.3km(1.74배)이고 2·3위도 47.0km·26.5km으로 말이 된다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`stubEmpty()`에:

```kotlin
        every { insightsRepository.driveRecords() } returns emptyList()
```

```kotlin
    given("records") {
        `when`("주행이 하나도 없으면") {
            then("셋 다 null이다 — 「역대」라는 값 자체가 없다") {
                stubEmpty()
                val records = service.insights(12).records

                records.longestDistance shouldBe null
                records.longestDuration shouldBe null
                records.bestEfficiency shouldBe null
            }
        }

        `when`("세 기록이 오면") {
            then("종류별로 갈라 싣고 시각을 KST로 되돌린다") {
                stubEmpty()
                every { insightsRepository.driveRecords() } returns
                    listOf(
                        DriveRecordRow("distance", 3619, LocalDateTime.of(2024, 9, 13, 0, 50), BigDecimal("293.2"), 308, BigDecimal("241.4")),
                        DriveRecordRow("duration", 3619, LocalDateTime.of(2024, 9, 13, 0, 50), BigDecimal("293.2"), 308, BigDecimal("241.4")),
                        DriveRecordRow("efficiency", 3342, LocalDateTime.of(2024, 6, 2, 4, 31), BigDecimal("26.7"), 30, BigDecimal("15.3")),
                    )

                val records = service.insights(12).records

                records.longestDistance!!.driveId shouldBe 3619
                // 2024-09-13 00:50 UTC → KST 09:50
                records.longestDistance!!.startedAt shouldBe LocalDateTime.of(2024, 9, 13, 9, 50)
                records.longestDistance!!.distanceKm shouldBe BigDecimal("293.2")
                records.longestDuration!!.durationMin shouldBe 308
                records.bestEfficiency!!.driveId shouldBe 3342
                records.bestEfficiency!!.ratedRangeUsedKm shouldBe BigDecimal("15.3")
            }
        }

        `when`("효율 기록만 없으면") {
            then("나머지 둘은 그대로 오고 그것만 null이다") {
                stubEmpty()
                every { insightsRepository.driveRecords() } returns
                    listOf(
                        DriveRecordRow("distance", 1, LocalDateTime.of(2024, 1, 1, 0, 0), BigDecimal("10.0"), 20, BigDecimal("11.0")),
                        DriveRecordRow("duration", 1, LocalDateTime.of(2024, 1, 1, 0, 0), BigDecimal("10.0"), 20, BigDecimal("11.0")),
                    )

                val records = service.insights(12).records
                records.longestDistance shouldNotBe null
                records.bestEfficiency shouldBe null
            }
        }
    }
```

테스트 상단 import에 `io.kotest.matchers.shouldNotBe`를 더한다.

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :apps:daily-record:test --tests '*TeslaInsightsServiceTest*'`
Expected: FAIL — `Unresolved reference: driveRecords`

- [ ] **Step 3: 행 타입·리포지토리·SQL을 더한다**

`TeslaInsightsRows.kt`:

```kotlin
/**
 * 기록 하나. **세 종류가 같은 타입으로 온다** — 응답에서는 종류마다 다른 필드를 싣지만,
 * 뽑는 방법이 「`drives` 한 행」으로 같아서 행 타입을 가르면 매핑만 세 벌이 된다.
 *
 * `kind`는 `distance`·`duration`·`efficiency`다. **번역하지 않는다** — 서비스가 이 문자열로
 * 갈라 담고, 값이 늘면 컴파일이 아니라 그 `when`이 잡는다.
 *
 * 실측(2026-08-20)으로 `drives`의 다섯 컬럼에 NULL이 0건이라 전부 non-null이다.
 */
data class DriveRecordRow(
    val kind: String,
    val driveId: Long,
    val startedAtUtc: LocalDateTime,
    val distanceKm: BigDecimal,
    val durationMin: Int,
    val ratedRangeUsedKm: BigDecimal,
)
```

`TeslaInsightsRows.kt` 상단 import에 `java.time.LocalDateTime`을 더한다.

`TeslaInsightsRepository.kt`:

```kotlin
    /**
     * 역대 기록 셋(최장거리·최장시간·최고효율), 종류마다 최대 한 행.
     *
     * **파라미터가 없다 — `months` 범위를 따르지 않는다.** 범위가 바뀔 때마다 1등이 바뀌면
     * 기록이 아니다. `driveStats`의 `maxSpeedKmh`와 같은 규약이다.
     *
     * 주행이 하나도 없으면 빈 리스트다. 효율 기록만 없을 수도 있다(거리 하한 20km).
     */
    fun driveRecords(): List<DriveRecordRow>
```

`JdbcTeslaInsightsRepository.kt`:

```kotlin
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
```

`JdbcTeslaInsightsRepository.kt` 상단 import에 `java.time.LocalDateTime`을 더한다.

```kotlin
        /**
         * 역대 기록 셋을 `UNION ALL`로 한 번에 낸다. **`months`를 따르지 않는다.**
         *
         * **최고 효율에 거리 하한 20km가 있어야 한다.** 없으면 실측으로 0.2km 주행이
         * 8.2배로 1등이 된다 — 정격거리 표시가 움직이지 않을 만큼 짧은 주행이다. 20km면
         * 1등이 26.7km/15.3km(1.74배)로 말이 된다.
         *
         * **`ORDER BY`마다 `id`를 tie-breaker로 둔다.** 실측으로 최장거리와 최장시간이 같은
         * 주행(id 3619)이고, 동률이 나올 때 어느 것이 뽑힐지 실행마다 달라지면 안 된다.
         *
         * `ROUND`의 자릿수는 다른 집계와 맞춘다 — 거리는 소수 한 자리다.
         */
        private const val DRIVE_RECORDS_SQL = """
            WITH d AS (
                SELECT id, start_date, distance, duration_min,
                       start_rated_range_km - end_rated_range_km AS rated_used
                  FROM drives
                 WHERE end_date IS NOT NULL
            )
            (SELECT 'distance' AS kind, id AS drive_id, start_date AS started_at,
                    ROUND(distance::numeric, 1) AS distance_km, duration_min,
                    ROUND(rated_used, 1)        AS rated_range_used_km
               FROM d
              ORDER BY distance DESC, id
              LIMIT 1)
            UNION ALL
            (SELECT 'duration', id, start_date,
                    ROUND(distance::numeric, 1), duration_min, ROUND(rated_used, 1)
               FROM d
              ORDER BY duration_min DESC, id
              LIMIT 1)
            UNION ALL
            (SELECT 'efficiency', id, start_date,
                    ROUND(distance::numeric, 1), duration_min, ROUND(rated_used, 1)
               FROM d
              WHERE distance >= 20
                AND rated_used > 0
              ORDER BY distance / rated_used DESC, id
              LIMIT 1)
        """
```

- [ ] **Step 4: 응답 타입과 서비스를 더한다**

`TeslaInsightsDtos.kt` — `TeslaInsightsResponse`에:

```kotlin
    /** 역대 기록 셋. **`months`를 따르지 않는다** — 범위마다 1등이 바뀌면 기록이 아니다. */
    val records: InsightsRecords,
```

```kotlin
/**
 * 명예의 전당. **셋 다 null일 수 있다** — 주행이 하나도 없거나(전부 null), 20km 넘는 주행이
 * 없으면(`bestEfficiency`만 null) 「역대」라는 값 자체가 없다.
 *
 * **「최다 고도 상승」을 두지 않는다.** `positions.elevation`을 주행마다 훑어야 하는데 창이
 * 없는 3,000만 행 스캔이고, 얻는 것이 타일 한 칸이라 값이 비용을 못 넘는다.
 */
data class InsightsRecords(
    val longestDistance: DistanceRecord?,
    val longestDuration: DurationRecord?,
    val bestEfficiency: EfficiencyRecord?,
)

/**
 * `driveId`를 싣는 이유: 앱이 나중에 그 주행 상세로 보내고 싶어질 자리다. 지금 앱에 주행 상세
 * 화면이 없어 쓰이지 않지만, 안 실으면 그때 계약을 또 고쳐야 한다. 셋 다 같은 이유로 싣는다.
 */
data class DistanceRecord(
    val driveId: Long,
    /** 출발 시각(KST). */
    val startedAt: LocalDateTime,
    val distanceKm: BigDecimal,
)

data class DurationRecord(
    val driveId: Long,
    val startedAt: LocalDateTime,
    val durationMin: Int,
)

/**
 * **거리 하한 20km를 넘은 주행 중** 정격거리 대비 실주행이 가장 좋았던 것.
 *
 * **비율을 내지 않는다 — 분자와 분모를 준다.** 앱이 `distanceKm ÷ ratedRangeUsedKm`로 낸다.
 * 하한이 필요한 이유는 실측에 있다: 없으면 0.2km 주행이 8.2배로 1등이 된다.
 */
data class EfficiencyRecord(
    val driveId: Long,
    val startedAt: LocalDateTime,
    val distanceKm: BigDecimal,
    val ratedRangeUsedKm: BigDecimal,
)
```

`TeslaInsightsService`에:

```kotlin
        val records = insightsRepository.driveRecords().associateBy { it.kind }
```

```kotlin
            records =
                InsightsRecords(
                    longestDistance =
                        records[RECORD_DISTANCE]?.let {
                            DistanceRecord(it.driveId, TeslaTime.toKst(it.startedAtUtc), it.distanceKm)
                        },
                    longestDuration =
                        records[RECORD_DURATION]?.let {
                            DurationRecord(it.driveId, TeslaTime.toKst(it.startedAtUtc), it.durationMin)
                        },
                    bestEfficiency =
                        records[RECORD_EFFICIENCY]?.let {
                            EfficiencyRecord(it.driveId, TeslaTime.toKst(it.startedAtUtc), it.distanceKm, it.ratedRangeUsedKm)
                        },
                ),
```

`companion object`에:

```kotlin
        /** `DriveRecordRow.kind`의 값. **`DRIVE_RECORDS_SQL`의 문자열과 같아야 한다.** */
        private const val RECORD_DISTANCE = "distance"
        private const val RECORD_DURATION = "duration"
        private const val RECORD_EFFICIENCY = "efficiency"
```

- [ ] **Step 5: 통과를 확인하고 커밋한다**

Run: `./gradlew :apps:daily-record:test --tests '*TeslaInsightsServiceTest*'`
Expected: PASS

```bash
./gradlew spotlessApply
git add apps/daily-record/src/
git commit -m "feat: 역대 기록 셋을 낸다"
```

---

### Task 10: `GET /tesla/battery-window`

**Files:**
- Modify: `TeslaInsightsRows.kt`, `TeslaInsightsRepository.kt`, `JdbcTeslaInsightsRepository.kt`, `TeslaInsightsDtos.kt`, `TeslaInsightsService.kt`, `TeslaInsightsController.kt`, `TeslaInsightsServiceTest.kt`

**Interfaces:**
- Consumes: `TeslaTime.timelineWindowKst`·`toUtc`·`toKst`, `TeslaVehicleRepository.chargeSegments(windowStartUtc, windowEndUtc)`
- Produces:
  - `BatterySampleRow(dateUtc, batteryLevel, usableBatteryLevel)`
  - `ParkDrainRow(ratedKm, hours, samples)`
  - `TeslaInsightsRepository.batterySamples(windowStartUtc, windowEndUtc): List<BatterySampleRow>`
  - `TeslaInsightsRepository.parkDrainSince(sinceUtc): ParkDrainRow`
  - `TeslaInsightsService.batteryWindow(hours: Int): TeslaBatteryWindowResponse`
  - `GET /tesla/battery-window?hours=48`

**스펙 JSON보다 `hours`를 되돌려 싣는다.** `/tesla/state-timeline`·`/tesla/drive-insights`가
받은 범위를 되싣는 것과 같다 — 앱이 무엇을 받았는지 알 수 있어야 한다.

**이 엔드포인트만 `positions`를 읽는다.** 창이 있으므로 BRIN이 듣는다(실측 48시간 32ms). **그리고 5분 슬롯으로 솎는다** — 실측 12,517행이 82개가 된다. 솎지 않으면 응답이 750KB이고, 상한인 168시간에서는 102,141행이라 6MB가 된다.

**`charges` 배열은 새 SQL을 쓰지 않는다.** `/tesla/state-timeline`이 이미 「범위에 걸치는 충전 구간을 범위 경계로 잘라서」 내고 있고(`TeslaVehicleRepository.chargeSegments`), 유령 거르기까지 같은 규칙이다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```kotlin
    given("batteryWindow — 범위 검증") {
        `when`("hours가 0이거나 169면") {
            then("400이다") {
                shouldThrow<CustomException> { service.batteryWindow(0) }
                shouldThrow<CustomException> { service.batteryWindow(169) }
            }
        }
    }

    given("batteryWindow — 응답") {
        fun stubWindow() {
            every { insightsRepository.batterySamples(any(), any()) } returns emptyList()
            every { insightsRepository.parkDrainSince(any()) } returns ParkDrainRow(BigDecimal.ZERO, BigDecimal.ZERO, 0)
            every { vehicleRepository.chargeSegments(any(), any()) } returns emptyList()
        }

        `when`("표본이 없으면") {
            then("null이 아니라 빈 배열이다") {
                stubWindow()
                val response = service.batteryWindow(48)

                response.samples shouldBe emptyList()
                response.charges shouldBe emptyList()
            }
        }

        `when`("끝이 요청 시각인지") {
            then("from이 to − hours다") {
                stubWindow()
                val response = service.batteryWindow(48)
                response.from shouldBe response.to.minusHours(48)
            }
        }

        `when`("표본이 오면") {
            then("시각을 KST로 되돌린다") {
                stubWindow()
                every { insightsRepository.batterySamples(any(), any()) } returns
                    listOf(BatterySampleRow(LocalDateTime.of(2026, 8, 18, 6, 2), 62, null))

                val sample = service.batteryWindow(48).samples.single()
                sample.at shouldBe LocalDateTime.of(2026, 8, 18, 15, 2)
                sample.batteryLevel shouldBe 62
                sample.usableBatteryLevel shouldBe null
            }
        }

        `when`("팬텀 드레인 표본이 0이면") {
            then("null이 아니라 samples 0으로 온다 — 앱이 그 줄을 감춘다") {
                stubWindow()
                service.batteryWindow(48).parkDrain.samples shouldBe 0
            }
        }
    }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :apps:daily-record:test --tests '*TeslaInsightsServiceTest*'`
Expected: FAIL — `Unresolved reference: batteryWindow`

- [ ] **Step 3: 행 타입·리포지토리·SQL을 더한다**

`TeslaInsightsRows.kt`:

```kotlin
/**
 * SOC 표본 하나. **5분 슬롯마다 첫 행 하나만 온다** — 자세한 근거는 `BATTERY_SAMPLES_SQL`.
 *
 * `usableBatteryLevel`은 **대부분 null이다**(실측 최근 30일 3.0%만 채워짐).
 * 0으로 바꾸지 않는다 — 「0%였다」와 「모른다」는 다르다.
 */
data class BatterySampleRow(
    val dateUtc: LocalDateTime,
    val batteryLevel: Int,
    val usableBatteryLevel: Int?,
)

/**
 * 팬텀 드레인 합. **나누지 않는다 — 하락 정격거리와 그 시간을 함께 낸다.**
 * 앱이 `km/시간` 또는 `%/일`로 만든다.
 *
 * 집계 쿼리라 행은 늘 온다. 표본이 없으면 `samples`가 0이고 나머지는 0이다.
 */
data class ParkDrainRow(
    val ratedKm: BigDecimal,
    val hours: BigDecimal,
    val samples: Int,
)
```

`TeslaInsightsRepository.kt`:

```kotlin
    /**
     * 범위 안의 SOC 표본, 시각순. **5분 슬롯마다 하나로 솎아서 온다.**
     *
     * **이 인터페이스에서 `positions`를 읽는 유일한 자리다.** 범위가 있으므로 BRIN이
     * 듣는다(실측 48시간 32ms). 범위 없는 조회를 여기 더하지 마라 — 실측 11.7초다.
     */
    fun batterySamples(
        windowStartUtc: LocalDateTime,
        windowEndUtc: LocalDateTime,
    ): List<BatterySampleRow>

    /**
     * `sinceUtc` 이후 시작된 순수 주차 구간의 팬텀 드레인 합. **집계라 행은 늘 온다.**
     *
     * 「순수 주차」의 정의와 유령 충전 규칙은 `parkDrainMonthly`와 같다.
     */
    fun parkDrainSince(sinceUtc: LocalDateTime): ParkDrainRow
```

`JdbcTeslaInsightsRepository.kt`:

```kotlin
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

    /**
     * `rs.getInt`는 SQL NULL에 0을 준다. `JdbcTeslaChargeRepository`·`JdbcTeslaVehicleRepository`가
     * 각자 같은 확장을 갖고 있고 **본문도 똑같이 쓴다** — 셋 다 private이라 부딪히지 않는다.
     */
    private fun ResultSet.nullableInt(column: String): Int? = getObject(column) as Int?
```

`JdbcTeslaInsightsRepository.kt` 상단 import에 `java.math.BigDecimal`과 `java.sql.ResultSet`을 더한다.

```kotlin
        /**
         * **5분 슬롯마다 첫 행 하나만 남긴다.**
         *
         * 초안 스펙은 「48시간이면 수백 개라 솎지 않는다」였는데 실측(2026-08-20)이
         * **12,517행**이었다(약 750KB). 상한인 168시간에서는 102,141행이라 6MB가 된다.
         * 솎으면 48시간 82개·168시간 423개다.
         *
         * **잃는 것이 거의 없는 이유:** TeslaMate는 차가 깨어 있을 때만 위치를 쌓는다.
         * 12,517행이 48시간 중 주행·충전한 몇 시간에 몰려 있고 주차 중에는 애초에 행이 없다.
         * 즉 솎아서 잃는 것은 **주행 중의 초 단위 해상도뿐**이고, 48시간을 한 화면에 그리는
         * 차트에서 그 해상도는 픽셀로도 안 보인다. (`/tesla/charges/{id}/curve`가 1,700개를
         * 안 줄이는 것과 다른 판단인 이유는 자릿수다 — 거기는 한 세션의 곡선이 주인공이다.)
         *
         * **`date`는 슬롯 경계가 아니라 실제 표본 시각이다.** 5분 눈금으로 옮기면 없는 시각의
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
         * `LEAD`를 전체 주행 위에서 돌리고, 겹침 판정에 `c.end_date IS NOT NULL`을 건다.
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
                       d.end_rated_range_km - LEAD(d.start_rated_range_km) OVER w AS drop_km
                  FROM drives d
                 WHERE d.end_date IS NOT NULL
                WINDOW w AS (ORDER BY d.start_date)
            )
            SELECT COUNT(*)                 AS samples,
                   ROUND(SUM(p.drop_km), 1) AS rated_km,
                   ROUND(SUM(EXTRACT(epoch FROM (p.to_date - p.from_date)) / 3600.0)::numeric, 1) AS hours
              FROM park p
             WHERE p.to_date IS NOT NULL
               AND p.from_date >= :since
               AND NOT EXISTS (SELECT 1
                                 FROM charging_processes c
                                WHERE c.end_date IS NOT NULL
                                  AND c.start_date < p.to_date
                                  AND c.end_date   > p.from_date)
        """
```

- [ ] **Step 4: 응답 타입을 더한다**

`TeslaInsightsDtos.kt`:

```kotlin
/**
 * 개요 화면의 충전 레벨 카드 하나를 채운다. **시각은 전부 KST다.**
 *
 * **이 응답만 `positions`를 읽는다.** 창이 있으므로 괜찮고, 표본은 5분 슬롯으로 솎아서 온다.
 */
data class TeslaBatteryWindowResponse(
    /** 받은 범위를 되돌려 싣는다. */
    val hours: Int,
    /** 범위 시작(KST) = `to` − `hours`시간. */
    val from: LocalDateTime,
    /**
     * 범위 끝(KST) = **요청 시각**. 자정에 맞추지 않는다 — 화면의 오른쪽 끝이 「지금」이어야
     * 한다. `/tesla/state-timeline`이 내린 결정과 같다.
     */
    val to: LocalDateTime,
    /**
     * SOC 표본, 오래된 것부터. **5분마다 최대 하나다**(실측 48시간 82개).
     *
     * 기록이 없으면 빈 배열이다 — 404가 아니다.
     */
    val samples: List<BatterySample>,
    /**
     * 이 창 안의 충전 구간, **범위 경계로 잘려서 온다.** 앱이 선 위에 다른 색으로 겹쳐 그린다.
     * 마감되지 않은 유령 세션은 빠진다(`/tesla/state-timeline`과 같은 규칙이다).
     */
    val charges: List<TimeSegment>,
    /** 최근 7일 팬텀 드레인. **`hours`와 무관한 고정 7일**이다 — 아래 참고. */
    val parkDrain: ParkDrain,
)

/**
 * `at`은 **그 슬롯의 실제 표본 시각**이다(5분 눈금으로 옮기지 않는다 — 없는 시각의 값이 된다).
 */
data class BatterySample(
    val at: LocalDateTime,
    val batteryLevel: Int,
    /**
     * **대부분 null이다**(실측 최근 30일 3.0%만 채워짐). 주 계열은 `batteryLevel`이고,
     * 이 값은 있을 때만 찍는 보조 계열이다 — 이것으로 선을 그리면 거의 다 끊긴다.
     */
    val usableBatteryLevel: Int?,
)

/**
 * 주차 중 정격거리가 얼마나 샜나. **창(`hours`)을 따르지 않고 최근 7일 고정이다** —
 * 48시간 안에 순수 주차 구간이 하나도 없는 날이 흔해서, 고정해야 숫자가 늘 나온다.
 *
 * **나누지 않는다.** 하락 정격거리와 그 시간을 함께 내고, 앱이 `km/시간` 또는 `%/일`로 만든다.
 */
data class ParkDrain(
    /** 하락 정격거리 합(km). **음수 구간도 부호 그대로 들어 있다.** */
    val ratedKm: BigDecimal,
    /** 그 구간들의 시간 합. */
    val hours: BigDecimal,
    /** 몇 구간에서 나왔나. **0이면 앱이 그 줄을 감춘다.** */
    val samples: Int,
)
```

- [ ] **Step 5: 서비스와 컨트롤러를 더한다**

`TeslaInsightsService`:

```kotlin
    /**
     * 쿼리 셋이 전부다. 서비스가 하는 일은 **범위 계산과 KST 되돌리기뿐**이다 —
     * 솎기와 범위 자르기는 SQL이 한다.
     *
     * **`parkDrain`만 창을 따르지 않는다.** 48시간 안에 순수 주차 구간이 하나도 없는 날이
     * 흔해서, 최근 7일로 고정해야 숫자가 늘 나온다.
     */
    fun batteryWindow(hours: Int): TeslaBatteryWindowResponse {
        if (hours !in MIN_HOURS..MAX_HOURS) {
            throw CustomException(ErrorCode.INVALID_REQUEST, "hours는 $MIN_HOURS~$MAX_HOURS 사이여야 합니다")
        }

        val (fromKst, toKst) = TeslaTime.timelineWindowKst(hours)
        val windowStart = TeslaTime.toUtc(fromKst)
        val windowEnd = TeslaTime.toUtc(toKst)
        val parkDrain = insightsRepository.parkDrainSince(TeslaTime.toUtc(toKst.minusDays(PARK_DRAIN_DAYS)))

        return TeslaBatteryWindowResponse(
            hours = hours,
            from = fromKst,
            to = toKst,
            samples =
                insightsRepository.batterySamples(windowStart, windowEnd).map {
                    BatterySample(
                        at = TeslaTime.toKst(it.dateUtc),
                        batteryLevel = it.batteryLevel,
                        usableBatteryLevel = it.usableBatteryLevel,
                    )
                },
            charges =
                vehicleRepository.chargeSegments(windowStart, windowEnd).map {
                    TimeSegment(from = TeslaTime.toKst(it.fromUtc), to = TeslaTime.toKst(it.toUtc))
                },
            parkDrain = ParkDrain(ratedKm = parkDrain.ratedKm, hours = parkDrain.hours, samples = parkDrain.samples),
        )
    }
```

`companion object`에:

```kotlin
        /** `/tesla/battery-window`의 범위. 기본 48시간, 1~168(=7일) — `/tesla/state-timeline`과 같다. */
        const val MIN_HOURS = 1
        const val MAX_HOURS = 168

        /**
         * 팬텀 드레인이 보는 고정 기간. **창(`hours`)과 무관하다** — 48시간 안에 순수 주차
         * 구간이 하나도 없는 날이 흔해서, 고정해야 숫자가 늘 나온다(실측 최근 7일 19구간).
         */
        private const val PARK_DRAIN_DAYS = 7L
```

`TeslaInsightsController`:

```kotlin
    /**
     * 개요 화면의 충전 레벨 카드 하나를 채운다. **표본은 5분마다 하나로 솎여서 나간다** —
     * 실측으로 48시간이 12,517행이라 그대로 내면 응답이 750KB이고, 상한인 168시간에서는
     * 6MB가 된다.
     *
     * 앱은 이 응답을 캐시하지 않는다 — 「최근 48시간」이 계속 움직인다.
     */
    @GetMapping("/battery-window")
    @Operation(summary = "배터리 창 — 최근 몇 시간의 SOC 표본·충전 구간·최근 7일 팬텀 드레인")
    fun batteryWindow(
        @Parameter(description = "거슬러 볼 시간(1~168)", example = DEFAULT_HOURS)
        @RequestParam(defaultValue = DEFAULT_HOURS)
        hours: Int,
    ): ResponseEntity<DataResponseBody<TeslaBatteryWindowResponse>> = ResponseEntity.ok(DataResponseBody(service.batteryWindow(hours)))
```

파일 끝에:

```kotlin
private const val DEFAULT_HOURS = "48"
```

- [ ] **Step 6: 통과를 확인하고 커밋한다**

Run: `./gradlew :apps:daily-record:test --tests '*TeslaInsightsServiceTest*'`
Expected: PASS

```bash
./gradlew spotlessApply
git add apps/daily-record/src/
git commit -m "feat: 배터리 창과 최근 팬텀 드레인을 낸다"
```

---

### Task 11: 실물 검증과 문서

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleController.kt`
- Create: `docs/changelog/2026-08-20-tesla-insights.md`

**왜 별도 태스크인가:** 앞의 열 태스크는 리포지토리를 목으로 대체한 단위 테스트만 봤다. `AGENTS.md`가 못 박아 둔 대로 **단위 테스트는 SQL의 컬럼 이름·타입·NULL을 하나도 검증하지 못한다.** 이 응답은 SQL 열넷에 컬럼이 마흔 개 넘게 있어, 오타 하나가 배포 뒤 500으로 나온다.

- [ ] **Step 1: 새 심볼이 어느 파일에 나오는지 센다**

`AGENTS.md`의 「커밋 전」 검사다. **개수가 아니라 목록에 무엇이 빠졌는지가 기준이다.**

```bash
for f in idleMin parkDrainRatedKm parkDrainSamples ratedRangeUsedKm chargingMin occurrences \
         costMissingCount speedBuckets speedEnergyBuckets chargeStartLevels chargeEndLevels \
         chargers regions records usableBatteryLevel; do
  echo "== $f"
  grep -rln "$f" apps/daily-record/src/main/kotlin/com/toy/backend/tesla --include='*.kt'
done
```

**목록마다 `TeslaInsightsDtos.kt`가 반드시 있어야 한다.** 없으면 그 값은 SQL·행 타입·서비스에 다 있어도 **앱까지 가지 않는다.** 이 저장소에서 실제로 세 번 난 결함이다(주의 영양소 3필드가 엔티티에만 있었고, `servingSizeKnown`이 응답 DTO에서 빠졌다).

- [ ] **Step 2: SQL 컬럼 이름과 `rs.getX` 이름을 기계로 대조한다**

눈으로 대 보지 않는다 — `AGENTS.md`가 「SQL 컬럼 수와 `setX` 인덱스가 어긋난 것도 눈이 아니라 스크립트로 대조해서 잡았다」고 적어 둔 자리다.

```bash
# SQL이 내는 별칭
grep -oE 'AS [a-z_]+' apps/daily-record/src/main/kotlin/com/toy/backend/tesla/JdbcTeslaInsightsRepository.kt \
  | sed 's/AS //' | sort -u > /tmp/sql-aliases.txt
# 매핑이 읽는 이름
grep -oE 'get[A-Za-z]+\("[a-z_]+"\)|nullableInt\("[a-z_]+"\)' \
  apps/daily-record/src/main/kotlin/com/toy/backend/tesla/JdbcTeslaInsightsRepository.kt \
  | grep -oE '"[a-z_]+"' | tr -d '"' | sort -u > /tmp/kt-columns.txt
# 매핑에는 있는데 SQL에 없는 이름 = 배포 뒤 500
comm -13 /tmp/sql-aliases.txt /tmp/kt-columns.txt
```

마지막 줄의 출력이 **비어 있어야 한다.** 별칭 없이 그대로 내는 컬럼(`date`·`battery_level`·`usable_battery_level`·`duration_min`)은 `/tmp/sql-aliases.txt`에 안 잡히므로, 그 넷만 출력에 남는 것은 정상이다. **그 밖의 이름이 하나라도 나오면 오타다.**

- [ ] **Step 3: 실제 TeslaMate DB로 띄워 호출한다**

```bash
./gradlew :apps:daily-record:bootRun
```

토큰을 받은 뒤 셋을 부른다(범위마다 SQL이 다르게 돈다):

```bash
curl -s -H "Authorization: Bearer $TOKEN" 'http://localhost:8080/tesla/insights?months=3'  | jq '.data | keys'
curl -s -H "Authorization: Bearer $TOKEN" 'http://localhost:8080/tesla/insights?months=0'  | jq '.data.monthly | length'
curl -s -H "Authorization: Bearer $TOKEN" 'http://localhost:8080/tesla/insights?months=61' -o /dev/null -w '%{http_code}\n'
curl -s -H "Authorization: Bearer $TOKEN" 'http://localhost:8080/tesla/battery-window?hours=48' | jq '.data.samples | length'
```

**확인할 것:**

| 무엇 | 기대 (실측 2026-08-20 기준) |
|---|---|
| `months=3` 응답 키 | 스펙의 열여섯 필드가 다 있다 |
| `months=0`의 `monthly` 길이 | **60** (2021-09 ~ 2026-08) |
| `months=61` | **400** |
| `battery-window` 표본 수 | **80~90** (5분 슬롯이 도는지. 12,000대면 솎기가 안 된 것이다) |
| `monthly` 마지막 칸의 `idleMin` | 이번 달 경과 분보다 작다. **44,640(달 전체)에 가까우면 「지금까지」가 안 걸린 것이다** |
| `monthly`의 `parkDrainSamples` | 달마다 **14~99** (0이 줄줄이면 유령 충전 판정이 빠진 것이다) |
| `records.bestEfficiency.distanceKm` | **20 이상** (0.2 같은 값이면 거리 하한이 빠진 것이다) |
| `chargeEndLevels` 마지막 칸 | 0이 아니다 (`LEAST(...)`가 빠지면 100%가 사라진다) |
| 전체 응답 크기 | `months=0`에서 50KB 안팎. **2초를 넘으면** 스펙대로 `driveTimes`/`chargeTimes`를 요일×3시간으로 묶는다 |

- [ ] **Step 4: 기존 컨트롤러의 KDoc이 새 계열을 가리키게 한다**

`TeslaVehicleController`의 클래스 KDoc 첫 문단을 바꾼다:

```kotlin
/**
 * 충전(`/tesla/charges` 하위)·차량(`/tesla/summary`·`/tesla/status`·`/tesla/battery-health`·
 * `/tesla/drive-insights`·`/tesla/state-timeline`)·통계(`TeslaInsightsController`)를 갈라 둔다 —
 * 읽는 테이블도 갱신 주기도 다르다. 한 파일에 여덟 엔드포인트를 두면 그 경계가 안 보인다.
 *
 * **`/tesla/drive-insights`는 `/tesla/insights`가 흡수했다.** 앱이 넘어간 뒤 지운다 —
 * 그때까지는 옛 계약을 쓰는 앱 버전이 살아 있다.
 *
 * 인증은 기존 SecurityConfig가 요구한다. `PublicEndpoint`를 두지 않는다 —
 * 주행 거리·위치·차량 상태는 충전 시각보다 더 직접적으로 생활을 드러낸다.
 */
```

- [ ] **Step 5: 변경 기록을 남긴다**

`docs/changelog/2026-08-20-tesla-insights.md`에 기존 파일들의 형식대로 적는다. **반드시 담을 것 넷:**

1. **`/tesla/insights` 신설** — `/tesla/drive-insights`의 여덟 필드를 이름까지 그대로 흡수했고, 옛 것은 앱이 넘어간 뒤 지운다.
2. **`positions`를 읽는 계열이 하나로 남았다** — `/tesla/battery-window`뿐이고 창이 있으며 5분 슬롯으로 솎는다.
3. **`TeslaVehicleRepository`의 네 메서드가 `months: Int` 대신 UTC 범위를 받는다** — 「전체 기간」을 SQL로 표현할 수 없어서다. `/tesla/drive-insights`의 동작은 그대로다.
4. **버킷 라벨이 `TeslaBuckets` 한곳으로 모였다** — SQL의 `CASE`와 어긋날 수 있는 자리가 늘지 않게.

- [ ] **Step 6: 전체 테스트와 커밋**

Run: `./gradlew :apps:daily-record:test`
Expected: PASS — Tesla 밖의 테스트도 포함해 전부.

```bash
./gradlew spotlessApply
git add apps/daily-record/src/ docs/
git commit -m "docs: 통계 응답 신설을 기록한다"
```

---

## 스펙의 테스트 목록이 어디서 검증되나

스펙 마지막 절이 요구한 여덟 가지다. **절반은 목으로 대체한 단위 테스트가 원리상 잡을 수
없다**(SQL의 조건이라 목이 그 자리에 없다) — 그것들은 Task 11의 실물 호출이 잡는다.

| 스펙이 요구한 것 | 어디서 |
|---|---|
| 팬텀 드레인 — 사이에 충전이 낀 구간이 빠지는지 | SQL의 `NOT EXISTS`. **Task 11**의 `parkDrainSamples`가 달마다 14~99인지로 확인 |
| 팬텀 드레인 — 마지막 주행 뒤가 안 잡히는지 | SQL의 `p.to_date IS NOT NULL`. Task 11 |
| 팬텀 드레인 — 유령 주행 앞뒤가 빠지는지 | SQL의 `d.end_date IS NOT NULL`. Task 11 |
| 팬텀 드레인 — 표본 0일 때 `null`이 아니라 `samples: 0` | **Task 5** 단위 테스트 |
| 정지 시간 — 뺀 값이 맞는지 | **Task 5**·**Task 6** 단위 테스트 |
| 정지 시간 — 음수가 안 나오는지 | **Task 5**·**Task 6** 단위 테스트(`coerceAtLeast(0)`) |
| 요일 집계 — `occurrences`가 실제 요일 수인지 | **Task 1** `TeslaTime` 단위 테스트(윤년·월말 경계 포함) |
| 빈 달이 `monthly`에서 사라지지 않는지 | **Task 5** 단위 테스트 |
| `months=0`이 전체 기간, `months=61`이 400 | **Task 4** 단위 테스트 + **Task 11** 실물 호출 |
| 지오펜스 0행일 때 `places`·`chargers`가 빈 배열 | **Task 4**·**Task 8** 단위 테스트 |
| KST 경계 — 월초 00:30 KST 주행이 그 달에 잡히는지 | SQL의 `AT TIME ZONE` 두 번. **Task 11**에서 `monthly` 첫 칸이 2021-09인지로 확인 |

---

## 이 계획이 하지 않는 것

- **`/tesla/drive-insights`를 지우지 않는다.** 앱 1단계가 서버 없이 나가므로 그 사이에는 옛 것만 쓰인다. 앱 2단계가 배포된 뒤 별건으로 지운다 — 그때 `TeslaVehicleService.driveInsights`·`TeslaDriveInsightsResponse`·컨트롤러 메서드와 KDoc이 함께 빠진다.
- **`/tesla/summary`의 `trend`를 없애지 않는다.** `trend`는 12개월 고정이고 `monthly`는 기간 칩을 따른다. 앱이 화면마다 다른 것을 본다.
- **`states`를 새로 읽지 않는다.** 정지 시간은 빼기로 낸다.
- **캐시를 두지 않는다.** 느리면 쿼리를 고친다.
- **「최다 고도 상승」을 두지 않는다.** `positions.elevation`을 주행마다 훑어야 하는 전 기간 스캔이고, 얻는 것이 타일 한 칸이다.
