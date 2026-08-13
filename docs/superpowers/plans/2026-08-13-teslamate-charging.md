# TeslaMate 충전 내역 조회·금액 수정 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** daily-record 앱에서 TeslaMate PostgreSQL의 충전 내역을 월·기간 단위로 조회하고 `charging_processes.cost` 한 컬럼만 수정한다.

**Architecture:** TeslaMate DB에는 JPA를 붙이지 않고 보조 `DataSource` + `JdbcClient`로만 접근한다. 보조 `DataSource` 빈을 등록하면 Spring Boot의 `DataSourceAutoConfiguration`이 backing off 하므로 기존 daily-record `DataSource`까지 **둘 다 명시적으로 정의**해야 한다. TeslaMate는 UTC 값을 타임존 없는 `timestamp` 컬럼에 넣으므로 서비스 계층에서 KST↔UTC를 변환한다.

**Tech Stack:** Kotlin 2.4.10, Spring Boot 4.1.0, `org.springframework.jdbc.core.simple.JdbcClient`, HikariCP, PostgreSQL, kotest `BehaviorSpec` + mockk

**Spec:** `docs/superpowers/specs/2026-08-13-teslamate-charging-design.md`

## Global Constraints

- 대상 모듈은 `apps/daily-record`다. 새 패키지는 `com.toy.backend.tesla`.
- **커밋 전 `./gradlew spotlessApply` 필수** (ktlint).
- 금액은 `BigDecimal`이다.
- 조회 응답은 `DataResponseBody`, 수정은 **204 No Content** (바디 없음).
- 타인/없는 리소스는 404 `ErrorCode.RESOURCE_NOT_FOUND`, 잘못된 요청은 400 `ErrorCode.INVALID_REQUEST`. **새 `Code` 구현 enum을 만들지 않는다** — 두 코드 모두 `common-core`의 `ErrorCode`에 이미 있다.
- 테스트는 kotest `BehaviorSpec` + mockk다. 격리 모드가 `InstancePerLeaf`라 `Given` 블록 본문은 리프마다 다시 실행된다.
- **컨트롤러 단위 테스트를 쓰지 않는다.** 이 저장소에 `*ControllerTest.kt`가 하나도 없다(`find . -name '*ControllerTest.kt' -not -path '*/build/*'` → 0). 컨트롤러는 Task 5의 실기동 확인으로 검증한다.
- TeslaMate 스키마는 **읽기 전용**이다. 유일한 쓰기는 `charging_processes.cost`다.
- 시간대 상수는 `Asia/Seoul`이다.
- `charging_processes.cost`는 이 DB에서 `numeric(10,2)`다 (손으로 확장해 둔 상태).
- 모든 쿼리는 `end_date IS NOT NULL`을 건다 — 목록·상세·금액수정 셋 다.

---

### Task 1: 목록 조회 서비스와 기간 해석

목록 응답 DTO, 리포지토리 인터페이스, 그리고 기간 파라미터를 UTC 경계로 번역하고 결과를 KST로 되돌리는 서비스를 만든다. **이 태스크가 이 기능에서 가장 틀리기 쉬운 곳이다** — 변환을 빠뜨리면 월초·월말 9시간이 옆 달로 샌다.

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaChargeDtos.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaChargeRepository.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaChargeService.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/tesla/TeslaChargeServiceTest.kt`

**Interfaces:**
- Consumes: `com.toy.backend.common.exception.CustomException`, `com.toy.backend.common.constant.ErrorCode` (둘 다 `common-core`)
- Produces:
  - `TeslaChargeListResponse(summary: ChargeSummary, items: List<ChargeListItem>)`
  - `ChargeSummary(count: Int, totalEnergyAddedKwh: BigDecimal?, totalCost: BigDecimal?)`
  - `ChargeListItem(id, startedAt, endedAt, durationMin, locationName, energyAddedKwh, startBatteryLevel, endBatteryLevel, cost)`
  - `ChargeRow` / `ChargeSummaryRow` — 리포지토리가 **UTC**로 돌려주는 행 타입
  - `interface TeslaChargeRepository { findList(startUtc, endUtcExclusive): List<ChargeRow>; summarize(startUtc, endUtcExclusive): ChargeSummaryRow }`
  - `TeslaChargeService.list(yearMonth: YearMonth?, from: LocalDate?, to: LocalDate?): TeslaChargeListResponse`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`apps/daily-record/src/test/kotlin/com/toy/backend/tesla/TeslaChargeServiceTest.kt`:

```kotlin
package com.toy.backend.tesla

import com.toy.backend.common.exception.CustomException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * **TeslaMate는 UTC 값을 타임존 없는 timestamp 컬럼에 넣는다.** 경계를 KST로 받아 UTC로 바꾸지
 * 않으면 월초·월말 9시간이 옆 달로 샌다. 이 테스트가 그 변환을 양방향으로 못 박는다.
 */
class TeslaChargeServiceTest :
    BehaviorSpec({
        val repository = mockk<TeslaChargeRepository>()
        val service = TeslaChargeService(repository)

        Given("yearMonth로 조회할 때") {
            val start = slot<LocalDateTime>()
            val end = slot<LocalDateTime>()
            every { repository.summarize(capture(start), capture(end)) } returns ChargeSummaryRow(0, null, null)
            every { repository.findList(any(), any()) } returns emptyList()

            service.list(YearMonth.of(2026, 8), null, null)

            Then("KST 8월이 UTC 경계로 번역된다") {
                start.captured shouldBe LocalDateTime.of(2026, 7, 31, 15, 0)
                end.captured shouldBe LocalDateTime.of(2026, 8, 31, 15, 0)
            }
        }

        Given("from·to로 조회할 때") {
            val start = slot<LocalDateTime>()
            val end = slot<LocalDateTime>()
            every { repository.summarize(capture(start), capture(end)) } returns ChargeSummaryRow(0, null, null)
            every { repository.findList(any(), any()) } returns emptyList()

            service.list(null, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 8, 13))

            Then("to는 포함이고 그 다음 날 자정(KST)이 상한이 된다") {
                start.captured shouldBe LocalDateTime.of(2026, 5, 31, 15, 0)
                end.captured shouldBe LocalDateTime.of(2026, 8, 13, 15, 0)
            }
        }

        Given("조회 결과가 있을 때") {
            every { repository.summarize(any(), any()) } returns
                ChargeSummaryRow(2, BigDecimal("60.5"), BigDecimal("18000"))
            every { repository.findList(any(), any()) } returns
                listOf(
                    ChargeRow(
                        id = 3312,
                        startDateUtc = LocalDateTime.of(2026, 8, 11, 13, 14),
                        endDateUtc = LocalDateTime.of(2026, 8, 11, 17, 31),
                        durationMin = 257,
                        locationName = "집",
                        energyAddedKwh = BigDecimal("48.2"),
                        startBatteryLevel = 18,
                        endBatteryLevel = 90,
                        cost = BigDecimal("14100"),
                    ),
                )

            val response = service.list(YearMonth.of(2026, 8), null, null)

            Then("시각이 UTC에서 KST로 되돌아온다") {
                response.items[0].startedAt shouldBe LocalDateTime.of(2026, 8, 11, 22, 14)
                response.items[0].endedAt shouldBe LocalDateTime.of(2026, 8, 12, 2, 31)
            }

            Then("나머지 필드는 그대로 실린다") {
                response.items[0].id shouldBe 3312L
                response.items[0].locationName shouldBe "집"
                response.items[0].cost shouldBe BigDecimal("14100")
            }

            Then("합계는 리포지토리 집계를 그대로 싣는다") {
                response.summary.count shouldBe 2
                response.summary.totalEnergyAddedKwh shouldBe BigDecimal("60.5")
                response.summary.totalCost shouldBe BigDecimal("18000")
            }
        }

        Given("파라미터가 잘못됐을 때") {
            Then("셋 다 없으면 400이다") {
                shouldThrow<CustomException> { service.list(null, null, null) }
            }

            Then("yearMonth와 from이 함께 오면 400이다") {
                shouldThrow<CustomException> {
                    service.list(YearMonth.of(2026, 8), LocalDate.of(2026, 8, 1), null)
                }
            }

            Then("from만 오면 400이다") {
                shouldThrow<CustomException> { service.list(null, LocalDate.of(2026, 8, 1), null) }
            }

            Then("to만 오면 400이다") {
                shouldThrow<CustomException> { service.list(null, null, LocalDate.of(2026, 8, 1)) }
            }

            Then("from이 to보다 늦으면 400이다") {
                shouldThrow<CustomException> {
                    service.list(null, LocalDate.of(2026, 8, 14), LocalDate.of(2026, 8, 13))
                }
            }
        }
    })
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

```bash
./gradlew :daily-record:test --tests '*TeslaChargeServiceTest*'
```

Expected: FAIL — `Unresolved reference: TeslaChargeRepository` 등 컴파일 오류.

- [ ] **Step 3: DTO를 만든다**

`apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaChargeDtos.kt`:

```kotlin
package com.toy.backend.tesla

import java.math.BigDecimal
import java.time.LocalDateTime

data class TeslaChargeListResponse(
    val summary: ChargeSummary,
    val items: List<ChargeListItem>,
)

/** 집계는 SQL이 계산한다 — 목록을 순회해 더하지 않는다. */
data class ChargeSummary(
    val count: Int,
    val totalEnergyAddedKwh: BigDecimal?,
    val totalCost: BigDecimal?,
)

data class ChargeListItem(
    val id: Long,
    /** KST. TeslaMate는 UTC로 저장한다. */
    val startedAt: LocalDateTime,
    val endedAt: LocalDateTime,
    val durationMin: Int?,
    /** 지오펜스 이름이 있으면 그것, 없으면 주소. 둘 다 없으면 null. */
    val locationName: String?,
    val energyAddedKwh: BigDecimal?,
    val startBatteryLevel: Int?,
    val endBatteryLevel: Int?,
    val cost: BigDecimal?,
)
```

- [ ] **Step 4: 리포지토리 인터페이스를 만든다**

`apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaChargeRepository.kt`:

```kotlin
package com.toy.backend.tesla

import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * TeslaMate DB 접근. **행 타입의 시각은 전부 UTC다** — TeslaMate가 타임존 없는 timestamp에
 * UTC 값을 넣기 때문이다. KST 변환은 서비스가 한다.
 */
interface TeslaChargeRepository {
    fun findList(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<ChargeRow>

    fun summarize(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): ChargeSummaryRow
}

data class ChargeRow(
    val id: Long,
    val startDateUtc: LocalDateTime,
    val endDateUtc: LocalDateTime,
    val durationMin: Int?,
    val locationName: String?,
    val energyAddedKwh: BigDecimal?,
    val startBatteryLevel: Int?,
    val endBatteryLevel: Int?,
    val cost: BigDecimal?,
)

data class ChargeSummaryRow(
    val count: Int,
    val totalEnergyAddedKwh: BigDecimal?,
    val totalCost: BigDecimal?,
)
```

- [ ] **Step 5: 서비스를 만든다**

`apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaChargeService.kt`:

```kotlin
package com.toy.backend.tesla

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * **`@Transactional`을 붙이지 않는다.** 기본 트랜잭션 매니저는 daily-record 커넥션의 것이라
 * TeslaMate 쪽 SQL에 아무 효력이 없다. 있는 것처럼 보이는 경계가 없는 것보다 나쁘다.
 * TeslaMate 쓰기는 UPDATE 한 건뿐이라 autocommit으로 충분하다.
 */
@Service
class TeslaChargeService(
    private val repository: TeslaChargeRepository,
) {
    fun list(
        yearMonth: YearMonth?,
        from: LocalDate?,
        to: LocalDate?,
    ): TeslaChargeListResponse {
        val (startUtc, endUtc) = resolveRange(yearMonth, from, to)
        val summary = repository.summarize(startUtc, endUtc)
        val items = repository.findList(startUtc, endUtc).map { it.toItem() }
        return TeslaChargeListResponse(
            summary = ChargeSummary(summary.count, summary.totalEnergyAddedKwh, summary.totalCost),
            items = items,
        )
    }

    /**
     * KST 경계를 UTC로 번역한다. `to`는 **포함**이라 그 다음 날 자정이 상한이 된다.
     * 기본값을 「이번 달」로 채우지 않는다 — 조회 범위가 응답에 실리지 않으므로 서버가 몰래 고른
     * 범위를 호출자가 모른 채 화면에 그리게 된다.
     */
    private fun resolveRange(
        yearMonth: YearMonth?,
        from: LocalDate?,
        to: LocalDate?,
    ): Pair<LocalDateTime, LocalDateTime> {
        if (yearMonth == null && from == null && to == null) {
            throw CustomException(ErrorCode.INVALID_REQUEST, "yearMonth 또는 from·to 중 하나는 필요합니다")
        }
        if (yearMonth != null && (from != null || to != null)) {
            throw CustomException(ErrorCode.INVALID_REQUEST, "yearMonth와 from·to는 함께 보낼 수 없습니다")
        }
        if (yearMonth != null) {
            return toUtc(yearMonth.atDay(1).atStartOfDay()) to
                toUtc(yearMonth.plusMonths(1).atDay(1).atStartOfDay())
        }
        if (from == null || to == null) {
            throw CustomException(ErrorCode.INVALID_REQUEST, "from과 to는 함께 보내야 합니다")
        }
        if (from.isAfter(to)) {
            throw CustomException(ErrorCode.INVALID_REQUEST, "from이 to보다 늦습니다")
        }
        return toUtc(from.atStartOfDay()) to toUtc(to.plusDays(1).atStartOfDay())
    }

    private fun ChargeRow.toItem() =
        ChargeListItem(
            id = id,
            startedAt = toKst(startDateUtc),
            endedAt = toKst(endDateUtc),
            durationMin = durationMin,
            locationName = locationName,
            energyAddedKwh = energyAddedKwh,
            startBatteryLevel = startBatteryLevel,
            endBatteryLevel = endBatteryLevel,
            cost = cost,
        )

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")

        fun toUtc(kst: LocalDateTime): LocalDateTime =
            kst.atZone(KST).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()

        fun toKst(utc: LocalDateTime): LocalDateTime =
            utc.atZone(ZoneOffset.UTC).withZoneSameInstant(KST).toLocalDateTime()
    }
}
```

- [ ] **Step 6: 테스트가 통과하는지 확인한다**

```bash
./gradlew :daily-record:test --tests '*TeslaChargeServiceTest*'
```

Expected: PASS (`Then` 블록 10개).

- [ ] **Step 7: 포맷 후 커밋**

```bash
./gradlew spotlessApply
git add apps/daily-record/src/main/kotlin/com/toy/backend/tesla apps/daily-record/src/test/kotlin/com/toy/backend/tesla
git commit -m "feat: TeslaMate 충전 내역 목록 조회 서비스를 만든다"
```

---

### Task 2: 상세 조회 서비스

목록에서 항목을 누르면 볼 상세다. `charging_processes` 본체 필드와 `charges` 집계를 합친다.

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaChargeDtos.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaChargeRepository.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaChargeService.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/tesla/TeslaChargeServiceTest.kt`

**Interfaces:**
- Consumes: Task 1의 `TeslaChargeRepository`, `TeslaChargeService`, `TeslaChargeService.toKst`
- Produces:
  - `TeslaChargeDetailResponse` — 목록 항목의 필드 전부 + `energyUsedKwh`, `startRatedRangeKm`, `endRatedRangeKm`, `outsideTempAvg`, `geofenceName`, `address`, `maxPowerKw`, `avgPowerKw`, `fastCharger`, `fastChargerBrand`, `fastChargerType`
  - `ChargeDetailRow`, `ChargeStatsRow`
  - `TeslaChargeRepository.findDetail(id: Long): ChargeDetailRow?`, `findChargeStats(id: Long): ChargeStatsRow`
  - `TeslaChargeService.detail(id: Long): TeslaChargeDetailResponse`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`TeslaChargeServiceTest.kt`의 마지막 `Given` 블록 **뒤에** 붙인다 (`})` 직전):

```kotlin
        Given("상세를 조회할 때") {
            every { repository.findDetail(3312L) } returns
                ChargeDetailRow(
                    id = 3312,
                    startDateUtc = LocalDateTime.of(2026, 8, 11, 13, 14),
                    endDateUtc = LocalDateTime.of(2026, 8, 11, 17, 31),
                    durationMin = 257,
                    energyAddedKwh = BigDecimal("48.2"),
                    energyUsedKwh = BigDecimal("51.0"),
                    startBatteryLevel = 18,
                    endBatteryLevel = 90,
                    startRatedRangeKm = BigDecimal("72.4"),
                    endRatedRangeKm = BigDecimal("361.8"),
                    outsideTempAvg = BigDecimal("27.5"),
                    geofenceName = "집",
                    address = "서울특별시 강남구 …",
                    cost = BigDecimal("14100"),
                )
            every { repository.findChargeStats(3312L) } returns
                ChargeStatsRow(
                    maxPowerKw = 11,
                    avgPowerKw = BigDecimal("10.4"),
                    fastCharger = false,
                    fastChargerBrand = null,
                    fastChargerType = null,
                )

            val detail = service.detail(3312L)

            Then("시각이 KST로 되돌아온다") {
                detail.startedAt shouldBe LocalDateTime.of(2026, 8, 11, 22, 14)
                detail.endedAt shouldBe LocalDateTime.of(2026, 8, 12, 2, 31)
            }

            Then("장소는 지오펜스 이름과 주소를 따로 싣는다") {
                detail.geofenceName shouldBe "집"
                detail.address shouldBe "서울특별시 강남구 …"
            }

            Then("charges 집계가 실린다") {
                detail.maxPowerKw shouldBe 11
                detail.avgPowerKw shouldBe BigDecimal("10.4")
                detail.fastCharger shouldBe false
            }
        }

        Given("charges 샘플이 하나도 없는 오래된 세션을 조회할 때") {
            every { repository.findDetail(9L) } returns
                ChargeDetailRow(
                    id = 9,
                    startDateUtc = LocalDateTime.of(2026, 1, 1, 0, 0),
                    endDateUtc = LocalDateTime.of(2026, 1, 1, 1, 0),
                    durationMin = 60,
                    energyAddedKwh = null,
                    energyUsedKwh = null,
                    startBatteryLevel = null,
                    endBatteryLevel = null,
                    startRatedRangeKm = null,
                    endRatedRangeKm = null,
                    outsideTempAvg = null,
                    geofenceName = null,
                    address = null,
                    cost = null,
                )
            every { repository.findChargeStats(9L) } returns
                ChargeStatsRow(null, null, null, null, null)

            val detail = service.detail(9L)

            // 0은 「0kW로 충전했다」는 뜻이 되어 없는 데이터와 구분되지 않는다.
            Then("출력·충전기 필드가 0이 아니라 null이다") {
                detail.maxPowerKw shouldBe null
                detail.avgPowerKw shouldBe null
                detail.fastCharger shouldBe null
                detail.fastChargerBrand shouldBe null
            }
        }

        Given("없는 id로 상세를 조회할 때") {
            every { repository.findDetail(404L) } returns null

            Then("404다") {
                shouldThrow<CustomException> { service.detail(404L) }
            }
        }
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

```bash
./gradlew :daily-record:test --tests '*TeslaChargeServiceTest*'
```

Expected: FAIL — `Unresolved reference: ChargeDetailRow`.

- [ ] **Step 3: 상세 응답 DTO를 더한다**

`TeslaChargeDtos.kt` 끝에 붙인다:

```kotlin
/**
 * 목록은 `locationName` 하나로 합치지만 상세는 `geofenceName`과 `address`를 따로 낸다 —
 * 「집」이라고만 적힌 항목의 실제 주소를 확인하는 것이 상세를 여는 이유 중 하나다.
 *
 * 효율(added/used)과 kWh당 단가(cost/added)는 서버에서 계산하지 않는다. 두 값이 다 내려가니
 * 앱에서 나눗셈 한 번이면 되고, 분모가 0이거나 null일 때의 처리를 서버가 정해 버리면 화면이 그것을 따라야 한다.
 */
data class TeslaChargeDetailResponse(
    val id: Long,
    val startedAt: LocalDateTime,
    val endedAt: LocalDateTime,
    val durationMin: Int?,
    val energyAddedKwh: BigDecimal?,
    /** 벽에서 뽑아쓴 양. 구버전 데이터에서 null일 수 있다. */
    val energyUsedKwh: BigDecimal?,
    val startBatteryLevel: Int?,
    val endBatteryLevel: Int?,
    val startRatedRangeKm: BigDecimal?,
    val endRatedRangeKm: BigDecimal?,
    val outsideTempAvg: BigDecimal?,
    val geofenceName: String?,
    val address: String?,
    val cost: BigDecimal?,
    /** charges 샘플이 없으면 아래 다섯은 전부 null이다 — 0이 아니다. */
    val maxPowerKw: Int?,
    val avgPowerKw: BigDecimal?,
    val fastCharger: Boolean?,
    val fastChargerBrand: String?,
    val fastChargerType: String?,
)
```

- [ ] **Step 4: 리포지토리 인터페이스와 행 타입을 더한다**

`TeslaChargeRepository.kt`의 인터페이스 본문에 두 메서드를 더한다:

```kotlin
    /** 없으면 null. 진행 중(`end_date IS NULL`)인 행도 없는 것으로 본다. */
    fun findDetail(id: Long): ChargeDetailRow?

    /** 샘플이 하나도 없어도 행은 온다 — 모든 필드가 null인 행이다. */
    fun findChargeStats(id: Long): ChargeStatsRow
```

같은 파일 끝에 행 타입을 더한다:

```kotlin
data class ChargeDetailRow(
    val id: Long,
    val startDateUtc: LocalDateTime,
    val endDateUtc: LocalDateTime,
    val durationMin: Int?,
    val energyAddedKwh: BigDecimal?,
    val energyUsedKwh: BigDecimal?,
    val startBatteryLevel: Int?,
    val endBatteryLevel: Int?,
    val startRatedRangeKm: BigDecimal?,
    val endRatedRangeKm: BigDecimal?,
    val outsideTempAvg: BigDecimal?,
    val geofenceName: String?,
    val address: String?,
    val cost: BigDecimal?,
)

data class ChargeStatsRow(
    val maxPowerKw: Int?,
    val avgPowerKw: BigDecimal?,
    val fastCharger: Boolean?,
    val fastChargerBrand: String?,
    val fastChargerType: String?,
)
```

- [ ] **Step 5: 서비스에 `detail`을 더한다**

`TeslaChargeService.kt`의 `list` 아래, `resolveRange` 위에 넣는다:

```kotlin
    fun detail(id: Long): TeslaChargeDetailResponse {
        val row = repository.findDetail(id) ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        val stats = repository.findChargeStats(id)
        return TeslaChargeDetailResponse(
            id = row.id,
            startedAt = toKst(row.startDateUtc),
            endedAt = toKst(row.endDateUtc),
            durationMin = row.durationMin,
            energyAddedKwh = row.energyAddedKwh,
            energyUsedKwh = row.energyUsedKwh,
            startBatteryLevel = row.startBatteryLevel,
            endBatteryLevel = row.endBatteryLevel,
            startRatedRangeKm = row.startRatedRangeKm,
            endRatedRangeKm = row.endRatedRangeKm,
            outsideTempAvg = row.outsideTempAvg,
            geofenceName = row.geofenceName,
            address = row.address,
            cost = row.cost,
            maxPowerKw = stats.maxPowerKw,
            avgPowerKw = stats.avgPowerKw,
            fastCharger = stats.fastCharger,
            fastChargerBrand = stats.fastChargerBrand,
            fastChargerType = stats.fastChargerType,
        )
    }
```

- [ ] **Step 6: 테스트가 통과하는지 확인한다**

```bash
./gradlew :daily-record:test --tests '*TeslaChargeServiceTest*'
```

Expected: PASS (`Then` 블록 15개 — Task 1의 10개 + 이번 5개).

- [ ] **Step 7: 포맷 후 커밋**

```bash
./gradlew spotlessApply
git add apps/daily-record/src
git commit -m "feat: TeslaMate 충전 상세 조회를 더한다"
```

---

### Task 3: 금액 수정 서비스

TeslaMate DB에 쓰는 유일한 자리다.

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaChargeDtos.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaChargeRepository.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaChargeService.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/tesla/TeslaChargeServiceTest.kt`

**Interfaces:**
- Consumes: Task 1의 `TeslaChargeRepository`, `TeslaChargeService`
- Produces:
  - `ChargeCostRequest(cost: BigDecimal?)` — `@NotNull` + `@DecimalMin("0")` + `@Digits(integer = 8, fraction = 2)`
  - `TeslaChargeRepository.updateCost(id: Long, cost: BigDecimal): Int` — 영향 행 수
  - `TeslaChargeService.updateCost(id: Long, request: ChargeCostRequest)` — 반환 없음

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`TeslaChargeServiceTest.kt` 끝(`})` 직전)에 붙인다:

```kotlin
        Given("금액을 수정할 때") {
            every { repository.updateCost(3312L, BigDecimal("15000")) } returns 1

            Then("예외 없이 끝난다") {
                service.updateCost(3312L, ChargeCostRequest(BigDecimal("15000")))
            }
        }

        // 없는 id, 그리고 진행 중이라 UPDATE 필터에 걸린 행이 모두 영향 행 0으로 온다.
        Given("영향 행이 0일 때") {
            every { repository.updateCost(404L, any()) } returns 0

            Then("404다") {
                shouldThrow<CustomException> {
                    service.updateCost(404L, ChargeCostRequest(BigDecimal("15000")))
                }
            }
        }
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

```bash
./gradlew :daily-record:test --tests '*TeslaChargeServiceTest*'
```

Expected: FAIL — `Unresolved reference: ChargeCostRequest`.

- [ ] **Step 3: 요청 DTO를 더한다**

`TeslaChargeDtos.kt` 끝에 붙이고, 파일 상단 import에 `jakarta.validation.constraints.*`를 더한다:

```kotlin
/**
 * `@NotNull`이라 금액을 비울 수 없다 — 되돌리기를 두지 않기로 한 결정의 표현이다.
 *
 * `@Digits(integer = 8)`은 `charging_processes.cost`의 `numeric(10,2)` 상한(99,999,999.99)이다.
 * DB 오류가 아니라 400으로 돌려주려는 것이다.
 */
data class ChargeCostRequest(
    @field:NotNull
    @field:DecimalMin("0")
    @field:Digits(integer = 8, fraction = 2)
    val cost: BigDecimal?,
)
```

import 문 (파일 상단, 알파벳 순으로 넣는다 — ktlint가 순서를 본다):

```kotlin
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotNull
```

- [ ] **Step 4: 리포지토리에 `updateCost`를 더한다**

`TeslaChargeRepository.kt`의 인터페이스 본문에:

```kotlin
    /**
     * TeslaMate DB에 쓰는 **유일한** 자리다. 영향 행 수를 돌려준다 —
     * 없는 id와 진행 중인 행이 모두 0이 된다.
     */
    fun updateCost(
        id: Long,
        cost: BigDecimal,
    ): Int
```

- [ ] **Step 5: 서비스에 `updateCost`를 더한다**

`TeslaChargeService.kt`의 `detail` 아래에:

```kotlin
    /**
     * 영향 행 수로 404를 판정한다 — SELECT로 존재를 확인하고 UPDATE 하는 것보다 왕복이 하나 적고
     * 결과도 같다. 진행 중인 충전은 리포지토리 SQL의 `end_date IS NOT NULL`에 걸려 0이 된다.
     * 그것을 허용하면 TeslaMate가 세션을 마감하며 지오펜스 요금으로 cost를 덮어써 값이 조용히 사라진다.
     */
    fun updateCost(
        id: Long,
        request: ChargeCostRequest,
    ) {
        val cost = request.cost ?: throw CustomException(ErrorCode.INVALID_REQUEST, "cost는 필수입니다")
        if (repository.updateCost(id, cost) == 0) {
            throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        }
    }
```

`request.cost`가 nullable인 것은 `@NotNull`이 컨트롤러의 `@Valid`에서 걸리기 때문이다. 서비스가 직접 불릴 때를 대비해 한 줄 더 둔다.

- [ ] **Step 6: 테스트가 통과하는지 확인한다**

```bash
./gradlew :daily-record:test --tests '*TeslaChargeServiceTest*'
```

Expected: PASS (`Then` 블록 17개 — Task 2까지의 15개 + 이번 2개).

- [ ] **Step 7: 포맷 후 커밋**

```bash
./gradlew spotlessApply
git add apps/daily-record/src
git commit -m "feat: TeslaMate 충전 금액 수정을 더한다"
```

---

### Task 4: 컨트롤러

세 엔드포인트를 낸다. **이 저장소는 컨트롤러 단위 테스트를 쓰지 않는다** — 컴파일과 Task 5의 실기동으로 검증한다.

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaChargeController.kt`

**Interfaces:**
- Consumes: `TeslaChargeService.list`, `.detail`, `.updateCost`, `ChargeCostRequest`, `com.toy.backend.common.response.DataResponseBody`
- Produces: `GET /tesla/charges`, `GET /tesla/charges/{id}`, `PUT /tesla/charges/{id}/cost`

- [ ] **Step 1: 컨트롤러를 쓴다**

`apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaChargeController.kt`:

```kotlin
package com.toy.backend.tesla

import com.toy.backend.common.response.DataResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.YearMonth

/**
 * TeslaMate DB를 직접 읽는다. TeslaMate에는 쓸 수 있는 데이터 API가 없다 —
 * `/api`에 있는 것은 로깅 resume/suspend 둘뿐이고, 금액 수정은 LiveView 화면으로만 제공된다.
 *
 * 인증은 기본 SecurityConfig가 요구한다. `PublicEndpoint`를 두지 않는다 —
 * 충전 시각·장소·금액은 생활 패턴이 그대로 드러나는 값이다.
 */
@Tag(name = "충전 내역", description = "TeslaMate 충전 내역 API")
@RestController
@RequestMapping("/tesla/charges")
class TeslaChargeController(
    private val service: TeslaChargeService,
) {
    @GetMapping
    @Operation(summary = "충전 내역 조회 — 연월(yearMonth) 또는 기간(from·to). 둘 중 하나는 필수")
    fun list(
        @Parameter(description = "조회 연월", example = "2026-08")
        @RequestParam(required = false)
        @DateTimeFormat(pattern = "yyyy-MM")
        yearMonth: YearMonth?,
        @Parameter(description = "시작일(포함)", example = "2026-06-01")
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        from: LocalDate?,
        @Parameter(description = "종료일(포함)", example = "2026-08-13")
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        to: LocalDate?,
    ): ResponseEntity<DataResponseBody<TeslaChargeListResponse>> =
        ResponseEntity.ok(DataResponseBody(service.list(yearMonth, from, to)))

    @GetMapping("/{id}")
    @Operation(summary = "충전 상세 조회")
    fun detail(
        @PathVariable id: Long,
    ): ResponseEntity<DataResponseBody<TeslaChargeDetailResponse>> =
        ResponseEntity.ok(DataResponseBody(service.detail(id)))

    @PutMapping("/{id}/cost")
    @Operation(summary = "충전 금액 수정")
    fun updateCost(
        @PathVariable id: Long,
        @Valid @RequestBody request: ChargeCostRequest,
    ): ResponseEntity<Void> {
        service.updateCost(id, request)
        return ResponseEntity.noContent().build()
    }
}
```

- [ ] **Step 2: 컴파일을 확인한다**

```bash
./gradlew :daily-record:compileKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: 기존 테스트가 안 깨졌는지 확인한다**

```bash
./gradlew :daily-record:test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: 포맷 후 커밋**

```bash
./gradlew spotlessApply
git add apps/daily-record/src
git commit -m "feat: TeslaMate 충전 내역 엔드포인트를 낸다"
```

---

### Task 5: DataSource 배선과 JdbcClient 리포지토리

**이 태스크가 실제로 DB에 닿는 유일한 곳이고, 단위 테스트가 못 잡는 것을 전부 안고 있다.** 반드시 앱을 띄워 확인한다.

**Files:**
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaMateDataSourceConfig.kt`
- Create: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/JdbcTeslaChargeRepository.kt`
- Modify: `apps/daily-record/src/main/resources/application.yml`
- Modify: `AGENTS.md`

**Interfaces:**
- Consumes: Task 1~3의 `TeslaChargeRepository`, `ChargeRow`, `ChargeSummaryRow`, `ChargeDetailRow`, `ChargeStatsRow`
- Produces: `teslaMateJdbcClient` 빈, `@Primary` daily-record `DataSource` 빈

- [ ] **Step 1: TeslaMate DB에 닿는지 먼저 확인한다**

구현하기 전에 경로부터 확인한다. 라즈베리파이의 TeslaMate postgres 포트가 열려 있는지 본다:

```bash
psql "postgresql://teslamate@192.168.0.10:5432/teslamate" -c "\d charging_processes" | grep -E 'cost|start_date|end_date'
```

기대: `cost | numeric(10,2)`, `start_date | timestamp without time zone`.

**닿지 않으면 여기서 멈추고 사용자에게 알린다.** TeslaMate 컨테이너의 postgres 포트가 호스트에 노출되어 있지 않을 수 있다 — 그 경우 daily-record 컨테이너를 같은 docker network에 붙이거나 포트를 열어야 하고, 그것은 코드가 아니라 배포 설정 변경이다.

- [ ] **Step 2: `application.yml`에 TeslaMate 블록을 더한다**

`holiday:` 블록 뒤, 파일 끝에 붙인다:

```yaml
# TeslaMate 전용 보조 DataSource. **읽기 + charging_processes.cost 쓰기만 한다.**
# 이 DataSource에는 EntityManagerFactory가 없으므로 ddl-auto가 TeslaMate 스키마에 닿지 않는다.
teslamate:
  datasource:
    url: jdbc:postgresql://${TESLAMATE_DB_HOST:localhost}:${TESLAMATE_DB_PORT:5432}/teslamate
    username: ${TESLAMATE_DB_USERNAME:teslamate}
    password: ${TESLAMATE_DB_PASSWORD:}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 2
```

비밀번호에 기본값을 **적지 않는다**.

- [ ] **Step 3: DataSource 설정을 쓴다**

`apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaMateDataSourceConfig.kt`:

```kotlin
package com.toy.backend.tesla

import com.zaxxer.hikari.HikariDataSource
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * **기본 DataSource까지 손으로 정의하는 이유가 있다.** 보조 DataSource 빈을 등록하는 순간
 * `DataSourceAutoConfiguration.PooledDataSourceConfiguration`의
 * `@ConditionalOnMissingBean({DataSource, XADataSource})`가 꺼져서 **기존 daily-record
 * DataSource가 통째로 사라진다.** 자동설정이 만들어 주던 것을 여기서 다시 만든다.
 *
 * `@Primary`가 빠지면 JPA·트랜잭션 매니저가 어느 쪽을 쓸지 몰라 기동에 실패하거나, 더 나쁘게는
 * **TeslaMate DB를 daily-record로 착각해 `ddl-auto: update`가 거기에 테이블을 만든다.**
 *
 * `teslaMateJdbcClient` 빈 때문에 `JdbcClientAutoConfiguration`이 backing off 한다 —
 * 이 앱의 다른 코드가 `JdbcClient`를 쓰지 않으므로 잃는 것이 없다.
 */
@Configuration
class TeslaMateDataSourceConfig {
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    fun dataSourceProperties(): DataSourceProperties = DataSourceProperties()

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    fun dataSource(dataSourceProperties: DataSourceProperties): HikariDataSource =
        dataSourceProperties.initializeDataSourceBuilder().type(HikariDataSource::class.java).build()

    @Bean
    @ConfigurationProperties("teslamate.datasource")
    fun teslaMateDataSourceProperties(): DataSourceProperties = DataSourceProperties()

    @Bean
    @ConfigurationProperties("teslamate.datasource.hikari")
    fun teslaMateDataSource(teslaMateDataSourceProperties: DataSourceProperties): HikariDataSource =
        teslaMateDataSourceProperties.initializeDataSourceBuilder().type(HikariDataSource::class.java).build()

    @Bean
    fun teslaMateJdbcClient(teslaMateDataSource: HikariDataSource): JdbcClient = JdbcClient.create(teslaMateDataSource)
}
```

- [ ] **Step 4: JdbcClient 리포지토리를 쓴다**

`apps/daily-record/src/main/kotlin/com/toy/backend/tesla/JdbcTeslaChargeRepository.kt`:

```kotlin
package com.toy.backend.tesla

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
    private val teslaMateJdbcClient: JdbcClient,
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
```

- [ ] **Step 5: 컴파일과 전체 테스트를 돌린다**

```bash
./gradlew :daily-record:test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: 앱을 띄워 실제로 확인한다 — 이 태스크의 진짜 검증**

단위 테스트는 SQL·두 DataSource 배선·`@Primary` 누락·컬럼 타입 불일치를 전부 목 뒤에 숨긴다. 앱을 띄운다:

```bash
TESLAMATE_DB_HOST=192.168.0.10 \
TESLAMATE_DB_USERNAME=teslamate \
TESLAMATE_DB_PASSWORD=<실제 비밀번호> \
./gradlew :daily-record:bootRun
```

로그인 토큰을 얻은 뒤 세 엔드포인트를 부른다 (`$TOKEN`은 발급받은 액세스 토큰):

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  'http://localhost:8080/tesla/charges?yearMonth=2026-08' | head -40

curl -s -H "Authorization: Bearer $TOKEN" \
  'http://localhost:8080/tesla/charges?from=2026-06-01&to=2026-08-13' | head -20

curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" \
  'http://localhost:8080/tesla/charges'
```

확인할 것:
- 목록의 `startedAt`이 **KST**로 나온다 (TeslaMate 웹 UI에 보이는 시각과 같아야 한다)
- `yearMonth=2026-08`의 첫 항목과 마지막 항목이 **8월 안에** 있다 (9시간이 안 샜다)
- 파라미터 없이 부르면 **400**이다
- 상세: `curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/tesla/charges/<목록의 id>`
- 없는 id 상세: **404**
- 금액 수정 후 **204**, 그리고 다시 조회했을 때 값이 바뀌어 있다:

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X PUT \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"cost": 15000}' \
  http://localhost:8080/tesla/charges/<id>/cost
```

- 금액 상한 검증: `-d '{"cost": 999999999}'` → **400**

- [ ] **Step 7: daily-record DB가 오염되지 않았는지 확인한다**

`@Primary`가 잘못 붙으면 `ddl-auto: update`가 TeslaMate DB에 daily-record 테이블을 만든다. 양쪽을 본다:

```bash
psql "postgresql://teslamate@192.168.0.10:5432/teslamate" -c '\dt' | grep -iE 'ledger|meal|dispatch|users'
```

기대: **아무것도 안 나온다.** 하나라도 나오면 `@Primary` 배선이 뒤집힌 것이니 되돌리고 고친다.

```bash
psql "postgresql://toy@localhost:5432/daily-record" -c '\dt' | grep -iE 'charging_processes|charges|geofences'
```

기대: **아무것도 안 나온다.**

- [ ] **Step 8: `AGENTS.md`를 갱신한다**

「## 구조」 섹션의 앱 설명 목록에 한 줄 더한다 (`- 앱 모듈은 common-core·common-auth를 의존하고…` 앞):

```markdown
- `daily-record`는 **TeslaMate PostgreSQL에 보조 DataSource로 붙는다**(`com.toy.backend.tesla`).
  읽기 전용에 가깝고 유일한 쓰기는 `charging_processes.cost`다. JPA를 붙이지 않고 `JdbcClient`만
  쓴다 — `ddl-auto: update`가 남의 스키마에 닿지 않게 하기 위해서다. 보조 DataSource 때문에
  **기본 DataSource도 `TeslaMateDataSourceConfig`가 손으로 정의한다**(자동설정이 backing off 한다)
```

- [ ] **Step 9: 포맷 후 커밋**

```bash
./gradlew spotlessApply
git add apps/daily-record/src apps/daily-record/src/main/resources/application.yml AGENTS.md
git commit -m "feat: TeslaMate DB에 보조 DataSource로 붙는다"
```

---

## 배포 메모

`deploy.sh`로 올리기 전에 라즈베리파이의 daily-record 컨테이너에 환경변수를 넣어야 한다.

```
TESLAMATE_DB_HOST=<teslamate postgres 호스트 또는 컨테이너명>
TESLAMATE_DB_PORT=5432
TESLAMATE_DB_USERNAME=teslamate
TESLAMATE_DB_PASSWORD=<비밀번호>
```

컨테이너명으로 붙이려면 daily-record 컨테이너가 TeslaMate의 docker network에 있어야 한다. 없으면 호스트 IP + 노출된 포트를 쓴다. **이것은 코드 변경이 아니라 배포 설정이고, 사용자가 처리한다.**
