# TeslaMate 월별 차량 요약·현재 상태 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** TeslaMate 보조 연결을 `drives`·`positions`·`states`까지 넓혀 월 단위 차량 요약(주행+충전)과 현재 상태를 내고, 금액이 빈 충전을 모아 보는 목록을 낸다.

**Architecture:** 기존 `com.toy.backend.tesla` 패키지에 차량용 컨트롤러·서비스·리포지토리를 새로 두고, 충전 쪽은 월 목록 엔드포인트를 걷어내고 미등록 목록을 더한다. KST↔UTC 변환은 `TeslaChargeService.companion`에서 공용 `TeslaTime`으로 옮겨 양쪽이 쓴다. 12개월 추이·이번 달·직전 달은 **한 벌의 월별 그룹 집계**에서 모두 나온다 — 직전 달이 12개월 창 안에 들기 때문이다.

**Tech Stack:** Kotlin 2.4.10, Spring Boot 4.1.0, `org.springframework.jdbc.core.simple.JdbcClient`, PostgreSQL, kotest `BehaviorSpec` + mockk

**Spec:** `docs/superpowers/specs/2026-08-13-tesla-vehicle-summary-design.md`

## Global Constraints

- 대상 모듈은 `apps/daily-record`, 패키지는 `com.toy.backend.tesla`.
- **커밋 전 `./gradlew spotlessApply` 필수** (ktlint는 import 순서를 본다).
- 금액은 `BigDecimal`이다.
- 조회 응답은 `DataResponseBody`, 수정은 204 No Content.
- 잘못된 요청은 400 `ErrorCode.INVALID_REQUEST`, 없는 리소스는 404 `ErrorCode.RESOURCE_NOT_FOUND`. **새 `Code` 구현 enum을 만들지 않는다** — 둘 다 `common-core`의 `ErrorCode`에 있다.
- 예외는 `CustomException(errorCode, vararg args)`.
- 테스트는 kotest `BehaviorSpec` + mockk. 격리 모드가 `InstancePerLeaf`라 각 `Given`은 자기 스텁을 스스로 준비한다.
- **컨트롤러 단위 테스트를 쓰지 않는다.** 이 저장소에 `*ControllerTest.kt`가 하나도 없다.
- **TeslaMate DB에 쓰는 것은 `charging_processes.cost` 하나뿐이다.** 주행·위치·상태는 읽기 전용.
- 모든 충전 쿼리는 `end_date IS NOT NULL`을 건다. **주행 쿼리도 같다** — 진행 중인 주행은 `distance`가 확정 전이다.
- 시간대는 `Asia/Seoul`. TeslaMate는 UTC 값을 타임존 없는 `timestamp`에 넣는다.
- **나눗셈을 서버가 하지 않는다.** km당 비용·전비·효율은 앱이 계산한다.
- 차량이 1대라 `car_id`를 파라미터로도 응답으로도 두지 않는다.

## 실측된 사실 (이 계획의 전제)

라즈베리파이의 실제 TeslaMate DB에서 확인했다.

| 항목 | 값 |
|---|---|
| `positions` 행 수 | **29,887,386** |
| `positions` 7일 창 + `ORDER BY date DESC LIMIT 1` | **123ms** (BRIN Bitmap Heap Scan, 75,102행) |
| `positions` 창 없이 `ORDER BY date DESC LIMIT 1` | **11,683ms** (Parallel Seq Scan) |
| `geofences` 행 수 | **0** |
| `drives` 행 수 | **5,051** |
| `ORDER BY id DESC`의 date vs `max(date)` | **일치** (둘 다 `2026-08-13 11:47:21.321`) |

**스펙의 폴백(창 없이 한 번 더)을 쓰지 않는다.** 11.7초다. 대신 `ORDER BY id DESC LIMIT 1`로 폴백한다 — `positions.id`는 serial이고 PK B-tree가 있어 즉시이며, TeslaMate가 시간 순으로 append만 하므로 최대 id가 최신 행이다. **이 불변식을 실제 DB에서 대조해 확인했다**(위 표 마지막 줄).

창을 먼저 돌리는 것을 유지하는 이유는 그쪽이 **import에도 견디기 때문이다.** TeslaMate에는 과거 데이터를 들여오는 기능이 있고, 그러면 오래된 날짜가 가장 큰 id를 받아 id 역순이 옛 행을 준다. 창은 날짜로 거르므로 그때도 맞는다. 123ms는 그 견고함의 값으로 싸다.

`geofences`가 0개라 `locationName`은 **오늘은 항상 null**이다. 판정 코드는 넣는다(TeslaMate에 지오펜스를 등록하는 순간 살아난다). 실데이터 검증은 오늘 불가능하다는 것을 알고 간다.

---

### Task 1: KST↔UTC 변환을 공용 `TeslaTime`으로 옮긴다

지금 변환은 `TeslaChargeService`의 `companion object`에 있다. 차량 쪽에서도 필요하므로 공용 자리로 옮긴다. **동작은 하나도 바뀌지 않는다** — 기존 테스트가 그대로 초록이어야 한다.

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaTime.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaChargeService.kt`

**Interfaces:**
- Consumes: 없음
- Produces:
  - `object TeslaTime`
  - `TeslaTime.toUtc(kst: LocalDateTime): LocalDateTime`
  - `TeslaTime.toKst(utc: LocalDateTime): LocalDateTime`
  - `TeslaTime.monthRangeUtc(yearMonth: YearMonth): Pair<LocalDateTime, LocalDateTime>` — 그 달의 KST 경계를 UTC 반개구간으로

- [ ] **Step 1: `TeslaTime`을 만든다**

`apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaTime.kt`:

```kotlin
package com.toy.backend.tesla

import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * TeslaMate는 **UTC 값을 타임존 없는 `timestamp` 컬럼**에 넣는다(Ecto `:utc_datetime_usec`).
 * 조회 경계는 KST로 받아 UTC로 바꾸고, 응답 시각은 UTC에서 KST로 되돌린다.
 * 빠뜨리면 월초·월말 9시간이 옆 달로 샌다.
 *
 * 충전과 차량 양쪽이 쓴다 — 한쪽에만 두면 다른 쪽이 자기 변환을 만든다.
 */
object TeslaTime {
    private val KST: ZoneId = ZoneId.of("Asia/Seoul")

    fun toUtc(kst: LocalDateTime): LocalDateTime = kst.atZone(KST).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()

    fun toKst(utc: LocalDateTime): LocalDateTime = utc.atZone(ZoneOffset.UTC).withZoneSameInstant(KST).toLocalDateTime()

    /** `2026-08` → `2026-07-31T15:00` ..< `2026-08-31T15:00` (UTC). 끝은 **포함하지 않는다**. */
    fun monthRangeUtc(yearMonth: YearMonth): Pair<LocalDateTime, LocalDateTime> =
        toUtc(yearMonth.atDay(1).atStartOfDay()) to toUtc(yearMonth.plusMonths(1).atDay(1).atStartOfDay())
}
```

- [ ] **Step 2: `TeslaChargeService`가 `TeslaTime`을 쓰게 한다**

`TeslaChargeService.kt`에서 `companion object` 블록 전체를 **지운다**:

```kotlin
    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")

        fun toUtc(kst: LocalDateTime): LocalDateTime = kst.atZone(KST).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()

        fun toKst(utc: LocalDateTime): LocalDateTime = utc.atZone(ZoneOffset.UTC).withZoneSameInstant(KST).toLocalDateTime()
    }
```

파일 안의 `toUtc(...)`/`toKst(...)` 호출을 전부 `TeslaTime.toUtc(...)`/`TeslaTime.toKst(...)`로 바꾼다. `resolveRange`의 `yearMonth` 분기는 `TeslaTime.monthRangeUtc(yearMonth)`로 바꾼다:

```kotlin
        if (yearMonth != null) {
            return TeslaTime.monthRangeUtc(yearMonth)
        }
```

쓰지 않게 된 import(`java.time.ZoneId`, `java.time.ZoneOffset`)를 지운다.

- [ ] **Step 3: 기존 테스트가 그대로 통과하는지 확인한다**

```bash
./gradlew :daily-record:test --tests '*TeslaChargeServiceTest*'
```

Expected: PASS. **동작을 바꾸는 리팩터링이 아니므로 테스트를 고치면 안 된다.** 하나라도 깨지면 옮기는 과정에서 뭔가 바뀐 것이니 되돌려 다시 본다.

- [ ] **Step 4: 포맷 후 커밋**

```bash
./gradlew spotlessApply
git add apps/daily-record/src
git commit -m "refactor: KST↔UTC 변환을 TeslaTime으로 옮긴다"
```

---

### Task 2: 충전 월 목록을 걷어내고 미등록 목록을 낸다

`GET /tesla/charges`(월/기간 목록)를 제거한다 — 목록이 `/tesla/summary`로 들어가면서 부를 곳이 없어진다. 대신 **금액이 빈 충전을 기간 없이 최신순으로** 내는 엔드포인트를 낸다.

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaChargeDtos.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaChargeRepository.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaChargeService.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/tesla/TeslaChargeServiceTest.kt`

**Interfaces:**
- Consumes: Task 1의 `TeslaTime`. 기존 `ChargeRow`, `ChargeListItem`
- Produces:
  - `MissingCostResponse(totalCount: Int, items: List<ChargeListItem>)`
  - `TeslaChargeRepository.findMissingCost(limit: Int): List<ChargeRow>`
  - `TeslaChargeRepository.countMissingCost(): Int`
  - `TeslaChargeService.missingCost(limit: Int): MissingCostResponse`
  - `ChargeRow.toItem()`가 `internal`이 되어 Task 4의 요약 서비스도 쓴다

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`TeslaChargeServiceTest.kt`에서 **`list`를 부르는 `Given` 블록을 전부 지운다.** 지울 대상은 다음 넷이다.

- `Given("yearMonth로 조회할 때")`
- `Given("from·to로 조회할 때")`
- `Given("조회 결과가 있을 때")`
- `Given("파라미터가 잘못됐을 때")`

기간 해석 검증은 Task 4의 요약 서비스가 이어받는다(같은 규칙을 `TeslaTime.monthRangeUtc`가 담당한다). `ChargeRow`·`ChargeSummaryRow` import가 남아 있으면 그대로 둔다 — 아래 새 테스트가 `ChargeRow`를 쓴다.

그 자리에 미등록 목록 테스트를 넣는다 (스펙을 닫는 `})` 직전):

```kotlin
        Given("금액이 빈 충전을 조회할 때") {
            every { repository.countMissingCost() } returns 37
            every { repository.findMissingCost(50) } returns
                listOf(
                    ChargeRow(
                        id = 3120,
                        startDateUtc = LocalDateTime.of(2026, 8, 11, 13, 14),
                        endDateUtc = LocalDateTime.of(2026, 8, 11, 17, 31),
                        durationMin = 257,
                        locationName = null,
                        energyAddedKwh = BigDecimal("48.2"),
                        energyUsedKwh = BigDecimal("51.8"),
                        startBatteryLevel = 18,
                        endBatteryLevel = 90,
                        cost = null,
                    ),
                )

            val response = service.missingCost(50)

            Then("항목이 KST로 실린다") {
                response.items[0].id shouldBe 3120L
                response.items[0].startedAt shouldBe LocalDateTime.of(2026, 8, 11, 22, 14)
            }

            // 배지에 「미등록 37건」을 띄우고 채울수록 줄어드는 것을 본다.
            Then("totalCount는 limit과 무관한 전체 개수다") {
                response.totalCount shouldBe 37
                response.items.size shouldBe 1
            }
        }

        Given("limit이 범위 밖일 때") {
            Then("0이면 400이다") {
                shouldThrow<CustomException> { service.missingCost(0) }
                    .errorCode shouldBe ErrorCode.INVALID_REQUEST
            }

            Then("201이면 400이다") {
                shouldThrow<CustomException> { service.missingCost(201) }
                    .errorCode shouldBe ErrorCode.INVALID_REQUEST
            }
        }
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

```bash
./gradlew :daily-record:test --tests '*TeslaChargeServiceTest*'
```

Expected: FAIL — `Unresolved reference: missingCost`.

- [ ] **Step 3: 응답 DTO를 바꾼다**

`TeslaChargeDtos.kt`에서 **`TeslaChargeListResponse`와 `ChargeSummary`를 지운다** (월 목록이 사라졌고, 요약의 집계는 Task 3이 자기 타입으로 낸다). 그 자리에 넣는다:

```kotlin
/**
 * 금액이 빈 충전을 모아 보는 목록. **기간 파라미터가 없다** — 채워 넣으려는 사람에게 필요한 것은
 * 「어느 달의 빈 건」이 아니라 「빈 건 전부」다.
 *
 * `totalCount`는 `limit`과 무관한 전체 개수다. 앱이 배지에 띄우고 채울수록 줄어드는 것을 본다.
 */
data class MissingCostResponse(
    val totalCount: Int,
    val items: List<ChargeListItem>,
)
```

- [ ] **Step 4: 리포지토리에서 월 목록·합계를 걷어내고 미등록 조회를 더한다**

**인터페이스와 구현체를 같은 단계에서 함께 고친다.** 인터페이스에서만 지우면 `JdbcTeslaChargeRepository`의 `override`가 짝을 잃어 빌드가 깨진다.

`TeslaChargeRepository.kt`에서 `findList`·`summarize` 두 메서드와 `ChargeSummaryRow` data class를 **지운다**. Task 3이 월별 그룹 집계로 대체하고, 그 달의 목록은 Task 4가 `findMonthCharges`로 다시 낸다.

`JdbcTeslaChargeRepository.kt`에서도 `findList`·`summarize` 구현과 `LIST_SQL`·`SUMMARY_SQL` 상수를 **지운다**.

인터페이스 본문에 더한다:

```kotlin
    /** `cost IS NULL AND end_date IS NOT NULL`을 `start_date DESC`로. 기간 필터가 없다. */
    fun findMissingCost(limit: Int): List<ChargeRow>

    /** `limit`과 무관한 전체 개수. */
    fun countMissingCost(): Int
```

- [ ] **Step 5: 서비스를 바꾼다**

`TeslaChargeService.kt`에서 `list(...)`와 `resolveRange(...)`를 **지운다**. `java.time.LocalDate`·`java.time.YearMonth` import가 쓰이지 않게 되면 함께 지운다.

`private fun ChargeRow.toItem()`을 **클래스 밖으로 꺼내고 `internal`로 바꾼다.** Task 4의 요약 서비스가 같은 변환을 쓰는데, 클래스 안에 있으면 부를 수 없다. 클래스 닫는 `}` **뒤**, 파일 맨 끝에 둔다:

```kotlin
/** `ChargeRow`(UTC)를 응답 항목(KST)으로. 충전 목록과 요약이 같은 변환을 쓴다. */
internal fun ChargeRow.toItem() =
    ChargeListItem(
        id = id,
        startedAt = TeslaTime.toKst(startDateUtc),
        endedAt = TeslaTime.toKst(endDateUtc),
        durationMin = durationMin,
        locationName = locationName,
        energyAddedKwh = energyAddedKwh,
        energyUsedKwh = energyUsedKwh,
        startBatteryLevel = startBatteryLevel,
        endBatteryLevel = endBatteryLevel,
        cost = cost,
    )
```

`detail` 아래에 넣는다:

```kotlin
    fun missingCost(limit: Int): MissingCostResponse {
        if (limit !in MIN_LIMIT..MAX_LIMIT) {
            throw CustomException(ErrorCode.INVALID_REQUEST, "limit은 $MIN_LIMIT..$MAX_LIMIT 사이여야 합니다")
        }
        return MissingCostResponse(
            totalCount = repository.countMissingCost(),
            items = repository.findMissingCost(limit).map { it.toItem() },
        )
    }
```

클래스 끝에 상수를 둔다. **기본값 50은 컨트롤러의 `@RequestParam(defaultValue = "50")`에만 둔다** — 그쪽은 문자열 리터럴이라야 해서 여기 상수를 공유할 수 없고, 쓰이지 않는 `DEFAULT_LIMIT`를 여기 두면 죽은 코드가 된다.

```kotlin
    companion object {
        private const val MIN_LIMIT = 1
        private const val MAX_LIMIT = 200
    }
```

- [ ] **Step 6: 테스트가 통과하는지 확인한다**

```bash
./gradlew :daily-record:test --tests '*TeslaChargeServiceTest*'
```

Expected: PASS.

- [ ] **Step 7: 포맷 후 커밋**

```bash
./gradlew spotlessApply
git add apps/daily-record/src
git commit -m "feat: 금액이 빈 충전 목록을 내고 월 목록을 걷어낸다"
```

---

### Task 3: 월별 그룹 집계 인터페이스 — 주행과 충전

12개월 추이·이번 달·직전 달이 **한 벌의 그룹 집계**에서 나온다. 직전 달이 12개월 창 안에 들기 때문이다. 주행 한 번, 충전 한 번, 도합 두 쿼리다.

**이 태스크의 핵심은 월 경계다.** `date_trunc('month', start_date)`를 그냥 쓰면 **UTC 기준으로 잘려** KST 8월 1일 0시 30분 충전이 7월로 들어간다. 반드시 KST로 옮긴 뒤 자른다.

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleRepository.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaChargeRepository.kt`

**Interfaces:**
- Consumes: 없음(인터페이스 선언만. 구현은 Task 6)
- Produces:
  - `TeslaVehicleRepository.driveMonthly(startUtc, endUtcExclusive): List<DriveMonthRow>`
  - `TeslaVehicleRepository.findLatestPosition(): PositionRow?`
  - `TeslaVehicleRepository.findOpenState(): StateRow?`
  - `TeslaVehicleRepository.findActivity(): ActivityRow`
  - `TeslaVehicleRepository.findGeofences(): List<GeofenceRow>`
  - `DriveMonthRow`, `PositionRow`, `StateRow`, `ActivityRow`, `GeofenceRow`
  - `TeslaChargeRepository.chargeMonthly(startUtc, endUtcExclusive): List<ChargeMonthRow>`
  - `ChargeMonthRow`

- [ ] **Step 1: 충전 월별 집계를 인터페이스에 더한다**

`TeslaChargeRepository.kt`의 인터페이스 본문에:

```kotlin
    /**
     * 월별 충전 집계. **월 경계는 KST 기준**이다 — SQL이 UTC 값을 KST로 옮긴 뒤 자른다.
     * 데이터가 없는 달은 행이 오지 않는다(0행이지 0값이 아니다).
     */
    fun chargeMonthly(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<ChargeMonthRow>
```

같은 파일 끝에:

```kotlin
data class ChargeMonthRow(
    val month: YearMonth,
    val count: Int,
    val energyAddedKwh: BigDecimal?,
    val energyUsedKwh: BigDecimal?,
    val cost: BigDecimal?,
)
```

`java.time.YearMonth` import를 더한다.

- [ ] **Step 2: 차량 리포지토리 인터페이스를 만든다**

`apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleRepository.kt`:

```kotlin
package com.toy.backend.tesla

import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * TeslaMate의 주행·위치·상태를 읽는다. **전부 읽기 전용이다** — 쓰는 것은
 * `TeslaChargeRepository.updateCost` 하나뿐이다.
 *
 * 행 타입의 시각은 전부 UTC다. KST 변환은 서비스가 한다.
 */
interface TeslaVehicleRepository {
    /**
     * 월별 주행 집계. **월 경계는 KST 기준**이고 진행 중인 주행(`end_date IS NULL`)은 제외한다 —
     * 끝나기 전에는 `distance`가 확정 전이다. 데이터가 없는 달은 행이 오지 않는다.
     */
    fun driveMonthly(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<DriveMonthRow>

    /**
     * 가장 최근 위치 1행. 없으면 null.
     *
     * `positions`는 3,000만 행이고 `date`에 BRIN만 있어, 창 없는 `ORDER BY date DESC`는
     * 실측 11.7초다. 7일 창(실측 123ms)을 먼저 돌리고 비면 PK 역순으로 폴백한다.
     */
    fun findLatestPosition(): PositionRow?

    /** `states`의 열린 행(`end_date IS NULL`). 없으면 null. */
    fun findOpenState(): StateRow?

    /** 열린 충전·주행 행의 존재 여부. `state` 파생에 쓴다. */
    fun findActivity(): ActivityRow

    /** 전부 읽는다 — 지오펜스는 몇 개 수준이라 반경 판정을 서버에서 한다. */
    fun findGeofences(): List<GeofenceRow>
}

data class DriveMonthRow(
    val month: YearMonth,
    val count: Int,
    val distanceKm: BigDecimal?,
    val drivingMin: Int?,
)

data class PositionRow(
    val dateUtc: LocalDateTime,
    val latitude: BigDecimal?,
    val longitude: BigDecimal?,
    val batteryLevel: Int?,
    val usableBatteryLevel: Int?,
    val ratedRangeKm: BigDecimal?,
    val estRangeKm: BigDecimal?,
    val odometerKm: Double?,
    val insideTempC: BigDecimal?,
    val outsideTempC: BigDecimal?,
    val climateOn: Boolean?,
    val tpmsFl: BigDecimal?,
    val tpmsFr: BigDecimal?,
    val tpmsRl: BigDecimal?,
    val tpmsRr: BigDecimal?,
)

data class StateRow(
    /** `online`·`offline`·`asleep`. **번역하지 않는다** — 상류가 값을 늘리면 그대로 올라온다. */
    val state: String,
    val startDateUtc: LocalDateTime,
)

/**
 * TeslaMate는 `driving`·`charging`을 `states`에 **저장하지 않는다**
 * (`CREATE TYPE states_status AS ENUM ('online', 'offline', 'asleep')`).
 * 열린 행에서 파생시킨다.
 */
data class ActivityRow(
    val charging: Boolean,
    val driving: Boolean,
)

data class GeofenceRow(
    val name: String,
    val latitude: BigDecimal,
    val longitude: BigDecimal,
    val radiusM: Int,
)
```

- [ ] **Step 3: `chargeMonthly` SQL을 구현한다**

`JdbcTeslaChargeRepository`는 `TeslaChargeRepository`를 구현하므로, Step 1에서 인터페이스에 메서드를 더한 지금은 **컴파일이 깨져 있다.** 여기서 채운다. 차량 리포지토리(`TeslaVehicleRepository`)는 아직 구현체가 없지만 인터페이스뿐이라 컴파일에 영향이 없다 — 구현은 Task 6이다.

`JdbcTeslaChargeRepository.kt`에 더한다 (`findList`·`summarize`와 그 SQL 상수는 Task 2에서 이미 지웠다):

```kotlin
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
```

companion object에:

```kotlin
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
                   COUNT(*)                    AS row_count,
                   SUM(cp.charge_energy_added) AS energy_added_kwh,
                   SUM(cp.charge_energy_used)  AS energy_used_kwh,
                   SUM(cp.cost)                AS cost
              FROM charging_processes cp
             WHERE cp.end_date IS NOT NULL
               AND cp.start_date >= :start
               AND cp.start_date <  :end
             GROUP BY month_start
             ORDER BY month_start
        """
```

`java.time.LocalDate`와 `java.time.YearMonth` import를 더한다.

- [ ] **Step 4: 컴파일과 기존 테스트를 확인한다**

```bash
./gradlew :daily-record:test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: 포맷 후 커밋**

```bash
./gradlew spotlessApply
git add apps/daily-record/src
git commit -m "feat: 월별 그룹 집계 인터페이스와 충전 집계 SQL을 낸다"
```

---

### Task 4: 요약 서비스 — 달 병합과 12개월 추이

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleDtos.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleService.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/tesla/TeslaVehicleServiceTest.kt`

**Interfaces:**
- Consumes: Task 1의 `TeslaTime`, Task 2의 `internal fun ChargeRow.toItem()`, Task 3의 `TeslaVehicleRepository.driveMonthly`·`TeslaChargeRepository.chargeMonthly`·`ChargeMonthRow`·`DriveMonthRow`. **`findList`는 Task 2에서 지웠으므로 쓰지 않는다** — 그 달의 목록은 이 태스크가 새로 내는 `findMonthCharges`로 가져온다
- Produces:
  - `MonthlyStat(yearMonth, distanceKm, drivingMin, driveCount, energyAddedKwh, energyUsedKwh, cost, chargeCount)`
  - `TeslaSummaryResponse(month, previous, trend, charges)`
  - `TeslaChargeRepository.findMonthCharges(startUtc, endUtcExclusive): List<ChargeRow>`
  - `TeslaVehicleService.summary(yearMonth: YearMonth?): TeslaSummaryResponse`
  - `TeslaVehicleService.TREND_MONTHS = 12`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`apps/daily-record/src/test/kotlin/com/toy/backend/tesla/TeslaVehicleServiceTest.kt`:

```kotlin
package com.toy.backend.tesla

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * **12개월 추이·이번 달·직전 달이 한 벌의 그룹 집계에서 나온다** — 직전 달이 12개월 창 안에 든다.
 * 주행만 있는 달과 충전만 있는 달이 하나로 합쳐지는지, 없는 달이 0이 아니라 null인지가 핵심이다.
 */
class TeslaVehicleServiceTest :
    BehaviorSpec({
        val vehicleRepository = mockk<TeslaVehicleRepository>()
        val chargeRepository = mockk<TeslaChargeRepository>()
        val service = TeslaVehicleService(vehicleRepository, chargeRepository)

        Given("yearMonth로 요약을 조회할 때") {
            val start = slot<LocalDateTime>()
            val end = slot<LocalDateTime>()
            every { vehicleRepository.driveMonthly(capture(start), capture(end)) } returns emptyList()
            every { chargeRepository.chargeMonthly(any(), any()) } returns emptyList()
            every { chargeRepository.findMonthCharges(any(), any()) } returns emptyList()

            service.summary(YearMonth.of(2026, 8))

            // 12개월 창의 시작은 2025-09, 끝은 2026-09 직전이다. 둘 다 KST 경계를 UTC로 옮긴 값이다.
            Then("12개월 창이 UTC 경계로 번역된다") {
                start.captured shouldBe LocalDateTime.of(2025, 8, 31, 15, 0)
                end.captured shouldBe LocalDateTime.of(2026, 8, 31, 15, 0)
            }
        }

        Given("주행만 있는 달과 충전만 있는 달이 섞여 있을 때") {
            every { vehicleRepository.driveMonthly(any(), any()) } returns
                listOf(DriveMonthRow(YearMonth.of(2026, 8), 61, BigDecimal("842.3"), 1043))
            every { chargeRepository.chargeMonthly(any(), any()) } returns
                listOf(
                    ChargeMonthRow(YearMonth.of(2026, 8), 5, BigDecimal("186.4"), BigDecimal("201.7"), BigDecimal("52300")),
                    ChargeMonthRow(YearMonth.of(2026, 7), 4, BigDecimal("141.0"), BigDecimal("152.2"), BigDecimal("39800")),
                )
            every { chargeRepository.findMonthCharges(any(), any()) } returns emptyList()

            val response = service.summary(YearMonth.of(2026, 8))

            Then("이번 달은 주행과 충전이 한 항목으로 합쳐진다") {
                response.month.yearMonth shouldBe YearMonth.of(2026, 8)
                response.month.distanceKm shouldBe BigDecimal("842.3")
                response.month.driveCount shouldBe 61
                response.month.cost shouldBe BigDecimal("52300")
                response.month.chargeCount shouldBe 5
            }

            // 7월은 충전만 있다 — 주행 필드가 0이 아니라 null이어야 「기록이 없다」로 읽힌다.
            Then("충전만 있는 달은 주행 필드가 null이다") {
                response.previous.yearMonth shouldBe YearMonth.of(2026, 7)
                response.previous.cost shouldBe BigDecimal("39800")
                response.previous.distanceKm shouldBe null
                response.previous.driveCount shouldBe null
            }

            Then("추이는 기준 달 포함 12개월이고 오래된 것부터다") {
                response.trend.size shouldBe 12
                response.trend.first().yearMonth shouldBe YearMonth.of(2025, 9)
                response.trend.last().yearMonth shouldBe YearMonth.of(2026, 8)
            }

            Then("데이터가 없는 달도 자리를 채우고 값은 null이다") {
                val empty = response.trend.first()
                empty.distanceKm shouldBe null
                empty.cost shouldBe null
                empty.chargeCount shouldBe null
            }
        }

        Given("1월을 조회할 때") {
            every { vehicleRepository.driveMonthly(any(), any()) } returns emptyList()
            every { chargeRepository.chargeMonthly(any(), any()) } returns emptyList()
            every { chargeRepository.findMonthCharges(any(), any()) } returns emptyList()

            val response = service.summary(YearMonth.of(2026, 1))

            Then("직전 달은 전년 12월이다") {
                response.previous.yearMonth shouldBe YearMonth.of(2025, 12)
            }
        }

        Given("그 달의 충전 목록이 있을 때") {
            val start = slot<LocalDateTime>()
            val end = slot<LocalDateTime>()
            every { vehicleRepository.driveMonthly(any(), any()) } returns emptyList()
            every { chargeRepository.chargeMonthly(any(), any()) } returns emptyList()
            every { chargeRepository.findMonthCharges(capture(start), capture(end)) } returns
                listOf(
                    ChargeRow(
                        id = 3312,
                        startDateUtc = LocalDateTime.of(2026, 8, 11, 13, 14),
                        endDateUtc = LocalDateTime.of(2026, 8, 11, 17, 31),
                        durationMin = 257,
                        locationName = "집",
                        energyAddedKwh = BigDecimal("48.2"),
                        energyUsedKwh = BigDecimal("51.8"),
                        startBatteryLevel = 18,
                        endBatteryLevel = 90,
                        cost = BigDecimal("14100"),
                    ),
                )

            val response = service.summary(YearMonth.of(2026, 8))

            // 목록은 그 달만이다 — 12개월 창을 쓰면 안 된다.
            Then("목록은 그 달의 경계로 조회한다") {
                start.captured shouldBe LocalDateTime.of(2026, 7, 31, 15, 0)
                end.captured shouldBe LocalDateTime.of(2026, 8, 31, 15, 0)
            }

            Then("항목 시각이 KST로 되돌아온다") {
                response.charges[0].startedAt shouldBe LocalDateTime.of(2026, 8, 11, 22, 14)
                response.charges[0].endedAt shouldBe LocalDateTime.of(2026, 8, 12, 2, 31)
            }
        }

        Given("yearMonth가 없을 때") {
            Then("400이다") {
                shouldThrow<CustomException> { service.summary(null) }
                    .errorCode shouldBe ErrorCode.INVALID_REQUEST
            }
        }
    })
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

```bash
./gradlew :daily-record:test --tests '*TeslaVehicleServiceTest*'
```

Expected: FAIL — `Unresolved reference: TeslaVehicleService`.

- [ ] **Step 3: 목록 조회를 리포지토리에 더한다**

`TeslaChargeRepository.kt`의 인터페이스 본문에:

```kotlin
    /** 그 달의 충전 목록. 진행 중은 제외하고 `start_date DESC`. */
    fun findMonthCharges(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<ChargeRow>
```

`JdbcTeslaChargeRepository.kt`에 구현을 더한다. **Task 3에서 지운 `LIST_SQL`이 여기로 돌아온다** — 이름만 바뀐다:

```kotlin
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
```

companion object에:

```kotlin
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
```

- [ ] **Step 4: 응답 DTO를 만든다**

`apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleDtos.kt`:

```kotlin
package com.toy.backend.tesla

import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * 화면이 하나라 목록과 합계를 한 응답에 싣는다. 둘로 나누면 같은 화면이 두 번 부르고,
 * 그중 하나는 반드시 다른 하나를 기다린다.
 */
data class TeslaSummaryResponse(
    val month: MonthlyStat,
    val previous: MonthlyStat,
    /** 기준 달 포함 거슬러 12개월, 오래된 것부터. 데이터가 없는 달도 자리를 채운다. */
    val trend: List<MonthlyStat>,
    val charges: List<ChargeListItem>,
)

/**
 * 그 달에 기록이 없는 필드는 **0이 아니라 null**이다. 0은 「안 탔다」는 뜻이 되어
 * 「기록이 없다」와 구분되지 않는다.
 *
 * km당 비용·전비는 서버가 계산하지 않는다 — 분모가 0이거나 null일 때의 처리를 서버가
 * 정해 버리면 화면이 그것을 따라야 한다.
 */
data class MonthlyStat(
    val yearMonth: YearMonth,
    val distanceKm: BigDecimal?,
    val drivingMin: Int?,
    val driveCount: Int?,
    val energyAddedKwh: BigDecimal?,
    val energyUsedKwh: BigDecimal?,
    val cost: BigDecimal?,
    val chargeCount: Int?,
)
```

- [ ] **Step 5: 서비스를 만든다**

`apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleService.kt`:

```kotlin
package com.toy.backend.tesla

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import org.springframework.stereotype.Service
import java.time.YearMonth

/**
 * **`@Transactional`을 붙이지 않는다.** 기본 트랜잭션 매니저는 daily-record 커넥션의 것이라
 * TeslaMate 쪽 SQL에 아무 효력이 없다. 이 서비스는 읽기만 한다.
 */
@Service
class TeslaVehicleService(
    private val vehicleRepository: TeslaVehicleRepository,
    private val chargeRepository: TeslaChargeRepository,
) {
    /**
     * 12개월 추이·이번 달·직전 달이 **한 벌의 그룹 집계**에서 나온다 —
     * 직전 달이 12개월 창 안에 들기 때문이다. 주행 한 번, 충전 한 번, 목록 한 번이 전부다.
     */
    fun summary(yearMonth: YearMonth?): TeslaSummaryResponse {
        if (yearMonth == null) {
            throw CustomException(ErrorCode.INVALID_REQUEST, "yearMonth는 필수입니다")
        }
        val oldest = yearMonth.minusMonths((TREND_MONTHS - 1).toLong())
        val windowStart = TeslaTime.monthRangeUtc(oldest).first
        val windowEnd = TeslaTime.monthRangeUtc(yearMonth).second

        val drives = vehicleRepository.driveMonthly(windowStart, windowEnd).associateBy { it.month }
        val charges = chargeRepository.chargeMonthly(windowStart, windowEnd).associateBy { it.month }
        val trend = (0 until TREND_MONTHS).map { statOf(oldest.plusMonths(it.toLong()), drives, charges) }

        val (monthStart, monthEnd) = TeslaTime.monthRangeUtc(yearMonth)
        return TeslaSummaryResponse(
            month = statOf(yearMonth, drives, charges),
            previous = statOf(yearMonth.minusMonths(1), drives, charges),
            trend = trend,
            charges = chargeRepository.findMonthCharges(monthStart, monthEnd).map { it.toItem() },
        )
    }

    private fun statOf(
        month: YearMonth,
        drives: Map<YearMonth, DriveMonthRow>,
        charges: Map<YearMonth, ChargeMonthRow>,
    ): MonthlyStat {
        val drive = drives[month]
        val charge = charges[month]
        return MonthlyStat(
            yearMonth = month,
            distanceKm = drive?.distanceKm,
            drivingMin = drive?.drivingMin,
            driveCount = drive?.count,
            energyAddedKwh = charge?.energyAddedKwh,
            energyUsedKwh = charge?.energyUsedKwh,
            cost = charge?.cost,
            chargeCount = charge?.count,
        )
    }

    companion object {
        /** 기준 달을 포함해 거슬러 세는 개월 수. */
        const val TREND_MONTHS = 12
    }
}
```

`it.toItem()`은 Task 2가 `TeslaChargeService.kt`의 파일 레벨 `internal` 함수로 꺼내 둔 것이다. 같은 패키지라 import 없이 부를 수 있다 — 이 태스크에서 더 할 일은 없다.

- [ ] **Step 6: 테스트가 통과하는지 확인한다**

```bash
./gradlew :daily-record:test
```

Expected: BUILD SUCCESSFUL. `TeslaVehicleServiceTest`의 `Then` 블록 10개와 기존 `TeslaChargeServiceTest`가 모두 통과한다.

- [ ] **Step 7: 포맷 후 커밋**

```bash
./gradlew spotlessApply
git add apps/daily-record/src
git commit -m "feat: 월별 차량 요약을 낸다"
```

---

### Task 5: 상태 서비스 — 파생 state와 지오펜스 판정

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleDtos.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleService.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/tesla/TeslaVehicleServiceTest.kt`

**Interfaces:**
- Consumes: Task 3의 `PositionRow`·`StateRow`·`ActivityRow`·`GeofenceRow`와 네 조회 메서드, Task 1의 `TeslaTime`
- Produces:
  - `TeslaStatusResponse`, `TpmsBar`
  - `TeslaVehicleService.status(): TeslaStatusResponse`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`TeslaVehicleServiceTest.kt` 끝(`})` 직전)에 붙인다:

```kotlin
        Given("차가 충전 중일 때") {
            every { vehicleRepository.findLatestPosition() } returns
                PositionRow(
                    dateUtc = LocalDateTime.of(2026, 8, 13, 5, 2),
                    latitude = BigDecimal("37.5665"),
                    longitude = BigDecimal("126.9780"),
                    batteryLevel = 72,
                    usableBatteryLevel = 70,
                    ratedRangeKm = BigDecimal("312.4"),
                    estRangeKm = BigDecimal("288.0"),
                    odometerKm = 41203.8,
                    insideTempC = BigDecimal("31.5"),
                    outsideTempC = BigDecimal("33.0"),
                    climateOn = false,
                    tpmsFl = BigDecimal("2.9"),
                    tpmsFr = BigDecimal("2.9"),
                    tpmsRl = BigDecimal("2.8"),
                    tpmsRr = BigDecimal("2.9"),
                )
            every { vehicleRepository.findOpenState() } returns
                StateRow("online", LocalDateTime.of(2026, 8, 13, 0, 30))
            every { vehicleRepository.findActivity() } returns ActivityRow(charging = true, driving = false)
            every { vehicleRepository.findGeofences() } returns emptyList()

            val status = service.status()

            // states 테이블에는 online·offline·asleep 셋뿐이라, 충전 중에도 online으로 저장된다.
            Then("states가 online이어도 charging이다") {
                status.state shouldBe "charging"
            }

            Then("asOf와 stateSince가 KST로 되돌아온다") {
                status.asOf shouldBe LocalDateTime.of(2026, 8, 13, 14, 2)
                status.stateSince shouldBe LocalDateTime.of(2026, 8, 13, 9, 30)
            }

            Then("공기압은 bar 그대로 낸다") {
                status.tpmsBar?.fl shouldBe BigDecimal("2.9")
                status.tpmsBar?.rl shouldBe BigDecimal("2.8")
            }

            Then("지오펜스가 없으면 위치 이름이 null이다") {
                status.locationName shouldBe null
            }
        }

        Given("충전과 주행이 동시에 열려 있을 때") {
            every { vehicleRepository.findLatestPosition() } returns null
            every { vehicleRepository.findOpenState() } returns null
            every { vehicleRepository.findActivity() } returns ActivityRow(charging = true, driving = true)
            every { vehicleRepository.findGeofences() } returns emptyList()

            val status = service.status()

            // TeslaMate가 죽었다 살아나면 끝나지 않은 주행 행이 남는다. 그때 사실에 가까운 쪽은 충전이다.
            Then("charging이 이긴다") {
                status.state shouldBe "charging"
            }
        }

        Given("차가 주행 중일 때") {
            every { vehicleRepository.findLatestPosition() } returns null
            every { vehicleRepository.findOpenState() } returns
                StateRow("online", LocalDateTime.of(2026, 8, 13, 0, 30))
            every { vehicleRepository.findActivity() } returns ActivityRow(charging = false, driving = true)
            every { vehicleRepository.findGeofences() } returns emptyList()

            val status = service.status()

            Then("driving이다") {
                status.state shouldBe "driving"
            }
        }

        Given("충전도 주행도 아닐 때") {
            every { vehicleRepository.findLatestPosition() } returns null
            every { vehicleRepository.findOpenState() } returns
                StateRow("asleep", LocalDateTime.of(2026, 8, 13, 0, 30))
            every { vehicleRepository.findActivity() } returns ActivityRow(charging = false, driving = false)
            every { vehicleRepository.findGeofences() } returns emptyList()

            val status = service.status()

            // 모르는 값도 그대로 통과한다 — 상류가 states_status에 값을 늘려도 매핑이 틀리지 않는다.
            Then("states의 값이 그대로 나온다") {
                status.state shouldBe "asleep"
            }
        }

        Given("위치 기록이 하나도 없을 때") {
            every { vehicleRepository.findLatestPosition() } returns null
            every { vehicleRepository.findOpenState() } returns null
            every { vehicleRepository.findActivity() } returns ActivityRow(charging = false, driving = false)
            every { vehicleRepository.findGeofences() } returns emptyList()

            val status = service.status()

            // 「기록이 아직 없다」와 「못 읽었다」는 다르다 — 500이 아니라 빈 상태다.
            Then("asOf가 null인 빈 상태를 낸다") {
                status.asOf shouldBe null
                status.batteryLevel shouldBe null
                status.tpmsBar shouldBe null
                status.state shouldBe null
                status.stateSince shouldBe null
            }
        }

        Given("차가 지오펜스 반경 안에 있을 때") {
            every { vehicleRepository.findLatestPosition() } returns
                PositionRow(
                    dateUtc = LocalDateTime.of(2026, 8, 13, 5, 2),
                    latitude = BigDecimal("37.56650"),
                    longitude = BigDecimal("126.97800"),
                    batteryLevel = 72,
                    usableBatteryLevel = 70,
                    ratedRangeKm = null,
                    estRangeKm = null,
                    odometerKm = null,
                    insideTempC = null,
                    outsideTempC = null,
                    climateOn = null,
                    tpmsFl = null,
                    tpmsFr = null,
                    tpmsRl = null,
                    tpmsRr = null,
                )
            every { vehicleRepository.findOpenState() } returns null
            every { vehicleRepository.findActivity() } returns ActivityRow(charging = false, driving = false)
            every { vehicleRepository.findGeofences() } returns
                listOf(
                    // 약 3km 떨어진 것 — 반경 100m 밖이다.
                    GeofenceRow("회사", BigDecimal("37.59300"), BigDecimal("126.97800"), 100),
                    // 같은 좌표 — 반경 안이다.
                    GeofenceRow("집", BigDecimal("37.56650"), BigDecimal("126.97800"), 100),
                )

            val status = service.status()

            Then("반경 안의 지오펜스 이름이 나온다") {
                status.locationName shouldBe "집"
            }
        }

        Given("차가 어느 지오펜스 반경에도 없을 때") {
            every { vehicleRepository.findLatestPosition() } returns
                PositionRow(
                    dateUtc = LocalDateTime.of(2026, 8, 13, 5, 2),
                    latitude = BigDecimal("35.16650"),
                    longitude = BigDecimal("129.07800"),
                    batteryLevel = 72,
                    usableBatteryLevel = 70,
                    ratedRangeKm = null,
                    estRangeKm = null,
                    odometerKm = null,
                    insideTempC = null,
                    outsideTempC = null,
                    climateOn = null,
                    tpmsFl = null,
                    tpmsFr = null,
                    tpmsRl = null,
                    tpmsRr = null,
                )
            every { vehicleRepository.findOpenState() } returns null
            every { vehicleRepository.findActivity() } returns ActivityRow(charging = false, driving = false)
            every { vehicleRepository.findGeofences() } returns
                listOf(GeofenceRow("집", BigDecimal("37.56650"), BigDecimal("126.97800"), 100))

            val status = service.status()

            // 주소는 내지 않는다 — positions에 주소 참조가 없고 역지오코딩을 붙이지 않는다.
            Then("위치 이름이 null이다") {
                status.locationName shouldBe null
            }
        }
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

```bash
./gradlew :daily-record:test --tests '*TeslaVehicleServiceTest*'
```

Expected: FAIL — `Unresolved reference: status`.

- [ ] **Step 3: 상태 응답 DTO를 더한다**

`TeslaVehicleDtos.kt` 끝에:

```kotlin
/**
 * `positions`의 최신 1행 + `states`의 열린 행. **값과 `asOf`를 항상 함께 낸다** —
 * 주차 중에는 위치가 뜸하게 쌓여 몇 시간 전 값일 수 있고, 시각 없이 배터리 %만 보면
 * 지금 값으로 읽힌다.
 *
 * 좌표를 싣지 않는다 — 생활 동선이 그대로 드러나고, 앱이 지도를 그리지 않는다.
 */
data class TeslaStatusResponse(
    /** 위치 행의 시각(KST). 기록이 하나도 없으면 null이다. */
    val asOf: LocalDateTime?,
    /**
     * `charging`·`driving`·`online`·`offline`·`asleep`.
     * 앞의 둘은 열린 행에서 파생한 것이고, 나머지는 `states`의 값 그대로다.
     */
    val state: String?,
    /** `states`의 열린 행이 시작된 시각(KST). 파생 상태에는 해당하지 않아 null일 수 있다. */
    val stateSince: LocalDateTime?,
    val batteryLevel: Int?,
    val usableBatteryLevel: Int?,
    val ratedRangeKm: BigDecimal?,
    val estRangeKm: BigDecimal?,
    val odometerKm: Double?,
    val insideTempC: BigDecimal?,
    val outsideTempC: BigDecimal?,
    val climateOn: Boolean?,
    /** 반경 안의 지오펜스 이름. 없으면 null이다(주소는 내지 않는다). */
    val locationName: String?,
    /** 위치 기록이 없으면 통째로 null이다. */
    val tpmsBar: TpmsBar?,
)

/** TeslaMate 저장 단위인 bar 그대로. psi 병기는 앱이 한다. */
data class TpmsBar(
    val fl: BigDecimal?,
    val fr: BigDecimal?,
    val rl: BigDecimal?,
    val rr: BigDecimal?,
)
```

- [ ] **Step 4: 서비스에 `status`를 더한다**

`TeslaVehicleService.kt`의 `summary` 아래, `statOf` 위에 넣는다:

```kotlin
    fun status(): TeslaStatusResponse {
        val position = vehicleRepository.findLatestPosition()
        val openState = vehicleRepository.findOpenState()
        val activity = vehicleRepository.findActivity()
        return TeslaStatusResponse(
            asOf = position?.dateUtc?.let { TeslaTime.toKst(it) },
            state = resolveState(activity, openState),
            stateSince = openState?.startDateUtc?.let { TeslaTime.toKst(it) },
            batteryLevel = position?.batteryLevel,
            usableBatteryLevel = position?.usableBatteryLevel,
            ratedRangeKm = position?.ratedRangeKm,
            estRangeKm = position?.estRangeKm,
            odometerKm = position?.odometerKm,
            insideTempC = position?.insideTempC,
            outsideTempC = position?.outsideTempC,
            climateOn = position?.climateOn,
            locationName = position?.let { geofenceNameAt(it) },
            tpmsBar = position?.let { TpmsBar(it.tpmsFl, it.tpmsFr, it.tpmsRl, it.tpmsRr) },
        )
    }

    /**
     * `states` 테이블에는 `online`·`offline`·`asleep` 셋뿐이다
     * (`CREATE TYPE states_status AS ENUM (...)`). `charging`·`driving`은 열린 행에서 파생시킨다 —
     * 테이블 값만 내면 **충전 중에도 online으로만 나온다.**
     *
     * 충전을 먼저 보는 이유: TeslaMate가 죽었다 살아나면 끝나지 않은 주행 행이 남을 수 있고,
     * 그 상태로 충전을 시작하면 두 조건이 동시에 참이 된다. 그때 사실에 가까운 쪽은 충전이다.
     */
    private fun resolveState(
        activity: ActivityRow,
        openState: StateRow?,
    ): String? =
        when {
            activity.charging -> "charging"
            activity.driving -> "driving"
            else -> openState?.state
        }

    /**
     * 반경 안에 드는 것 중 가장 가까운 하나. 없으면 null이다.
     *
     * **판정을 서버에서 한다.** TeslaMate가 `cube`·`earthdistance`를 깔아 두지만, 그 확장은
     * 상류가 자기 필요로 깐 것이고 지오펜스는 몇 개 수준이다. 전부 읽어 재는 편이 낫다.
     */
    private fun geofenceNameAt(position: PositionRow): String? {
        val lat = position.latitude?.toDouble() ?: return null
        val lon = position.longitude?.toDouble() ?: return null
        return vehicleRepository
            .findGeofences()
            .map { it to distanceMeters(lat, lon, it.latitude.toDouble(), it.longitude.toDouble()) }
            .filter { (fence, distance) -> distance <= fence.radiusM }
            .minByOrNull { (_, distance) -> distance }
            ?.first
            ?.name
    }

    /** 하버사인. 지구를 구로 보고 재며, 수 km 규모에서 오차는 판정에 영향을 주지 않는다. */
    private fun distanceMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a =
            sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }
```

companion object에 상수를 더한다:

```kotlin
        private const val EARTH_RADIUS_M = 6_371_000.0
```

import를 더한다 (알파벳 순 제자리에):

```kotlin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
```

- [ ] **Step 5: 테스트가 통과하는지 확인한다**

```bash
./gradlew :daily-record:test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: 포맷 후 커밋**

```bash
./gradlew spotlessApply
git add apps/daily-record/src
git commit -m "feat: 차량 현재 상태를 낸다"
```

---

### Task 6: 컨트롤러 둘과 JdbcClient 구현, 그리고 실기동

**이 태스크가 실제로 DB에 닿는 유일한 곳이고, 단위 테스트가 못 잡는 것을 전부 안고 있다.**

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleController.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/JdbcTeslaVehicleRepository.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaChargeController.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/JdbcTeslaChargeRepository.kt`
- Modify: `AGENTS.md`

**Interfaces:**
- Consumes: Task 2~5의 서비스·인터페이스 전부
- Produces: `GET /tesla/summary`, `GET /tesla/status`, `GET /tesla/charges/missing-cost`

- [ ] **Step 1: 충전 컨트롤러에서 월 목록을 걷어내고 미등록 목록을 낸다**

`TeslaChargeController.kt`에서 `list(...)` 메서드 전체를 **지운다**. 쓰이지 않게 된 import(`org.springframework.format.annotation.DateTimeFormat`, `java.time.LocalDate`, `java.time.YearMonth`, `io.swagger.v3.oas.annotations.Parameter`, `org.springframework.web.bind.annotation.RequestParam`)를 정리한다 — 아래 새 메서드가 `Parameter`와 `RequestParam`을 다시 쓰므로 그 둘은 남긴다.

`detail` **위에** 넣는다:

```kotlin
    @GetMapping("/missing-cost")
    @Operation(summary = "금액이 빈 충전 조회 — 기간 무관, 최신순")
    fun missingCost(
        @Parameter(description = "최대 건수 (1~200)", example = "50")
        @RequestParam(required = false, defaultValue = "50")
        limit: Int,
    ): ResponseEntity<DataResponseBody<MissingCostResponse>> = ResponseEntity.ok(DataResponseBody(service.missingCost(limit)))
```

**`/missing-cost`를 `/{id}` 앞에 둔다.** Spring은 리터럴 경로를 템플릿보다 먼저 맞추므로 순서와 무관하게 동작하지만, 읽는 사람이 헷갈리지 않게 위에 둔다.

- [ ] **Step 2: 차량 컨트롤러를 만든다**

`apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaVehicleController.kt`:

```kotlin
package com.toy.backend.tesla

import com.toy.backend.common.response.DataResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.YearMonth

/**
 * 충전(`/tesla/charges/*`)과 차량(`/tesla/summary`·`/tesla/status`)을 갈라 둔다 —
 * 읽는 테이블도 갱신 주기도 다르다. 한 파일에 다섯 엔드포인트를 두면 그 경계가 안 보인다.
 *
 * 인증은 기존 SecurityConfig가 요구한다. `PublicEndpoint`를 두지 않는다 —
 * 주행 거리·위치·차량 상태는 충전 시각보다 더 직접적으로 생활을 드러낸다.
 */
@Tag(name = "차량", description = "TeslaMate 차량 요약·상태 API")
@RestController
@RequestMapping("/tesla")
class TeslaVehicleController(
    private val service: TeslaVehicleService,
) {
    @GetMapping("/summary")
    @Operation(summary = "월별 차량 요약 — 주행·충전 합계, 직전 달, 12개월 추이, 그 달의 충전 목록")
    fun summary(
        @Parameter(description = "조회 연월", example = "2026-08")
        @RequestParam(required = false)
        @DateTimeFormat(pattern = "yyyy-MM")
        yearMonth: YearMonth?,
    ): ResponseEntity<DataResponseBody<TeslaSummaryResponse>> = ResponseEntity.ok(DataResponseBody(service.summary(yearMonth)))

    @GetMapping("/status")
    @Operation(summary = "차량 현재 상태 — 값과 그 값의 기준 시각(asOf)을 함께 낸다")
    fun status(): ResponseEntity<DataResponseBody<TeslaStatusResponse>> = ResponseEntity.ok(DataResponseBody(service.status()))
}
```

- [ ] **Step 3: 충전 리포지토리에 미등록 조회를 구현한다**

`JdbcTeslaChargeRepository.kt`에 더한다:

```kotlin
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
            .sql(MISSING_COST_COUNT_SQL)
            .query { rs, _ -> rs.getInt("row_count") }
            .single()
```

companion object에:

```kotlin
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
             ORDER BY cp.start_date DESC
             LIMIT :limit
        """

        private const val MISSING_COST_COUNT_SQL = """
            SELECT COUNT(*) AS row_count
              FROM charging_processes cp
             WHERE cp.end_date IS NOT NULL
               AND cp.cost IS NULL
        """
```

- [ ] **Step 4: 차량 리포지토리를 구현한다**

`apps/daily-record/src/main/kotlin/com/toy/backend/tesla/JdbcTeslaVehicleRepository.kt`:

```kotlin
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
```

- [ ] **Step 5: 컴파일과 전체 테스트를 돌린다**

```bash
./gradlew :daily-record:test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: 앱을 띄워 실제로 확인한다 — 이 태스크의 진짜 검증**

```bash
TESLAMATE_DB_HOST=192.168.0.10 \
TESLAMATE_DB_USERNAME=teslamate \
TESLAMATE_DB_PASSWORD=<실제 비밀번호> \
./gradlew :daily-record:bootRun
```

`$TOKEN`은 발급받은 액세스 토큰이다.

```bash
curl -s -H "Authorization: Bearer $TOKEN" 'http://localhost:8080/tesla/summary?yearMonth=2026-08' | head -60
curl -s -H "Authorization: Bearer $TOKEN" 'http://localhost:8080/tesla/status'
curl -s -H "Authorization: Bearer $TOKEN" 'http://localhost:8080/tesla/charges/missing-cost?limit=5'
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" 'http://localhost:8080/tesla/summary'
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" 'http://localhost:8080/tesla/charges/missing-cost?limit=0'
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" 'http://localhost:8080/tesla/charges'
```

확인할 것:
- `summary`의 `trend`가 **12개**이고 `2025-09`부터 `2026-08`까지 빠짐없이 있다
- `month.yearMonth`가 `2026-08`, `previous.yearMonth`가 `2026-07`이다
- **`charges`의 첫·마지막 항목이 8월 안에 있다** (KST 월 경계가 샜는지 보는 자리다)
- `month.distanceKm`가 그럴듯한 값이다 (`drives` 5,051행 규모)
- `status`의 `asOf`가 KST이고 지금과 가깝다. `state`가 다섯 값 중 하나다
- `status`의 `tpmsBar` 네 값이 2~3 사이 bar다
- `status`의 `locationName`은 **지금은 null이 정상**이다 (지오펜스 0개)
- `missing-cost`의 `totalCount`가 `items.size`보다 크다(빈 건이 5건 넘게 있다면)
- `yearMonth` 없이 `summary` → **400**
- `limit=0` → **400**
- `GET /tesla/charges` → **404** (없어진 엔드포인트)

- [ ] **Step 7: 상태 쿼리의 실행 계획을 확인한다**

7일 창이 실제로 도는지 본다. `psql`로:

```sql
EXPLAIN ANALYZE
SELECT p.date, p.battery_level FROM positions p
 WHERE p.date >= now() - interval '7 days'
 ORDER BY p.date DESC LIMIT 1;
```

기대: `Bitmap Heap Scan` + 실행 시간 200ms 이내. 크게 벗어나면 창을 좁힌다(3일·1일).

그리고 PK 폴백이 실제로 최신 행을 주는지 대조한다:

```sql
SELECT (SELECT date FROM positions ORDER BY id DESC LIMIT 1) AS by_id,
       (SELECT max(date) FROM positions) AS by_date;
```

**두 값이 같으면** id 폴백이 정확하다. 다르면(과거 데이터를 import한 적이 있다는 뜻) 폴백을 지우고 창을 넓히는 쪽으로 바꾼다.

- [ ] **Step 8: daily-record DB가 오염되지 않았는지 확인한다**

```bash
psql "postgresql://teslamate@192.168.0.10:5432/teslamate" -c '\dt' | grep -iE 'ledger|meal|dispatch|users'
```

기대: **아무것도 안 나온다.**

- [ ] **Step 9: `AGENTS.md`를 갱신한다**

「## 구조」 섹션의 tesla 항목에 한 줄 덧붙인다:

```markdown
  충전 외에 `drives`·`positions`·`states`도 읽는다(`/tesla/summary`·`/tesla/status`).
  `positions`는 3,000만 행에 `date`가 BRIN뿐이라 **창 없는 `ORDER BY date DESC`가 11.7초**다 —
  7일 창을 먼저 돌리고 PK 역순으로 폴백한다. 월 집계는 `date_trunc`를 **KST로 옮긴 뒤** 자른다
  (UTC 기준으로 자르면 월초 9시간이 옆 달로 샌다)
```

- [ ] **Step 10: 포맷 후 커밋**

```bash
./gradlew spotlessApply
git add apps/daily-record/src AGENTS.md
git commit -m "feat: 차량 요약·상태 엔드포인트를 내고 TeslaMate 조회를 넓힌다"
```

---

## 배포 메모

**서버를 먼저 올리면 구버전 앱의 충전 화면이 404를 받는다.** `GET /tesla/charges`가 사라지기 때문이다. 사용자 2명·개인 배포라 앱 갱신과 붙여서 하면 되지만, 몇 시간 어긋나는 동안 그 화면만 깨진다는 것을 알고 한다.

환경변수는 #37에서 넣은 넷 그대로다. 새로 추가되는 것은 없다.
