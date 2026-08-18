# TeslaMate 누적 합계·충전 곡선(3단계) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `charging_processes` 전체 합(급속/완속 구분 포함)을 내는 `GET /tesla/charges/totals`와, 세션 하나의 kW 샘플을 내는 `GET /tesla/charges/{id}/curve`를 낸다.

**Architecture:** 1·2단계가 차량 계층(`TeslaVehicle*`)에 붙었던 것과 달리 **충전 계층(`TeslaCharge*`)에 붙인다** — 두 값이 답하는 질문이 「충전이 어땠나」다. 쿼리 둘 다 집계는 SQL이 하고 서비스는 행→DTO 변환과 404 판정만 한다. 새 파일이 없다.

**Tech Stack:** Kotlin 2.4.10, Spring Boot 4.1.0, `org.springframework.jdbc.core.simple.JdbcClient`, PostgreSQL(TeslaMate 보조 DataSource), kotest 6.2.2 `BehaviorSpec` + mockk 1.14.11

**Spec:** `docs/superpowers/specs/2026-08-17-tesla-battery-health-design.md` — **3단계 부분.** 다만 그 절은 한 문단 스케치라, **Task 5가 이 계획에서 확정한 내용으로 스펙을 채운다.**

**앞선 작업:** 1단계(`77a205c`)·2단계(`691523b`)가 `main`에 머지됐다. 이 계획은 그 위에서 시작한다.

**앱 저장소:** `../woori-haru`의 `docs/superpowers/specs/2026-08-17-vehicle-health-dashboard-design.md`가 이 API를 쓰는 화면 설계다. **Task 6이 그 문서를 갱신한다** — 3단계 API 확정, 1·2단계의 틀린 예측 정정, 보류 항목 판정.

## Global Constraints

- 대상 모듈은 `:daily-record`, 패키지는 `com.toy.backend.tesla`.
- **커밋 전 `./gradlew spotlessApply` 필수** (ktlint는 import 순서를 본다).
- **TeslaMate DB는 읽기만 한다.** 유일한 쓰기는 `TeslaChargeRepository.updateCost`이고 이 계획은 그것을 건드리지 않는다. 인덱스도 만들지 않는다 — 남의 스키마다.
- 조회 응답은 `DataResponseBody`. 없는 리소스는 404 `ErrorCode.RESOURCE_NOT_FOUND`(`CustomException(errorCode, id)`). **새 `Code` 구현 enum을 만들지 않는다.**
- `TeslaChargeService`에 **`@Transactional`을 붙이지 않는다**(클래스 KDoc에 이미 적혀 있다).
- 시각은 KST로 되돌려 응답에 싣는다(`TeslaTime.toKst`). TeslaMate는 UTC 값을 타임존 없는 `timestamp`에 넣는다.
- 소수는 `BigDecimal`. 반올림은 **SQL의 `ROUND(...)`가 한다.**
- **나눗셈을 서버가 하지 않는다.** 단가·전비는 앱이 낸다 — 서버는 앱이 옳게 나눌 수 있는 **분모**를 함께 준다.
- **`charges`를 조인하지 않는다**(485,830행). 급속 여부는 `charging_processes`만으로 파생한다 — 아래 실측 참조.
- 차량이 1대라 `car_id`를 파라미터로도 응답으로도 두지 않는다.
- 테스트는 kotest `BehaviorSpec` + mockk. 격리 모드가 `InstancePerLeaf`라 각 `Given`은 자기 스텁을 스스로 준비한다.
- **컨트롤러 단위 테스트를 쓰지 않는다.** 이 저장소에 `*ControllerTest.kt`가 하나도 없다.

## 1·2단계에서 배운 것 — 이 계획이 지키는 규칙

**지표별 조건은 격리하고, 모집단 정의 조건은 명시적으로 열거해 공유한다.**

1단계는 하나의 공통 WHERE에 두 지표를 얹어 같은 계열 지적을 세 번 받았다. 2단계는 쿼리를 나눠 피했지만, 그때 세운 판정 문구(「그 쿼리가 쓰지 않는 컬럼의 조건이 걸리면 결함」)가 너무 좁아 **모집단 정의**(`distance > 0`)까지 결함으로 판정하게 됐다. 이번 계획은 둘을 갈라 적는다.

**이 계획의 모집단 정의(두 SQL이 공유):**

| 조건 | 왜 |
|---|---|
| `end_date IS NOT NULL` | 진행 중인 충전은 값이 흔들린다. 저장소 전체가 이미 이 조건을 건다 |
| `charge_energy_added > 0 OR cost IS NOT NULL` | 「에너지가 들어갔거나, 돈을 냈거나」. 아래 실측 참조 |

**지표별 조건:** 없다. 두 SQL 모두 모집단 전체를 집계하고, 급속/완속과 미입력은 **`FILTER`로 가른다** — WHERE로 깎지 않으므로 어느 지표가 다른 지표의 표본을 줄일 길이 구조적으로 없다.

---

## 실측된 사실 (2026-08-18, 이 계획의 전제)

라즈베리파이의 실제 TeslaMate DB에서 확인했다.

| 항목 | 값 |
|---|---|
| `charging_processes` 완료 건수 | **484** |
| 그중 `charge_energy_added`가 0이거나 null(축퇴) | **11** |
| 축퇴인데 `cost`가 있는 것 | **1** (`id=15`, 2021-10-10, 10,360원) |
| **모집단(`> 0 OR cost IS NOT NULL`)** | **474** |
| `charges` 행 수 | **485,830** |
| `charges` 인덱스 | `charges_charging_process_id_index` (B-tree) 있음 |
| 한 세션의 샘플 수 | 급속 250~360, **완속 700~1,700** |

모집단 474건의 집계:

| 종류 | 건수 | added kWh | used kWh | 비용 | 미입력 | 미입력 used kWh |
|---|---|---|---|---|---|---|
| 급속 | 42 | 1,358.4 | 1,329.0 | 143,337 | 24 | 833.9 |
| 완속 | 432 | 16,083.6 | 16,868.2 | 3,501,225 | 11 | 143.1 |
| **합계** | **474** | **17,442.0** | **18,197.2** | **3,644,562** | **35** | **977.0** |

**모두 더해서 맞는다** — 42+432=474, 143,337+3,501,225=3,644,562, 24+11=35, 833.9+143.1=977.0.

### 실측이 설계를 정한 것 넷

**1. 급속 여부를 `charges` 조인 없이 파생한다.**

```
평균 전력 = charge_energy_added / duration_min * 60   (kW)
급속      = 평균 전력 >= 15
```

`bool_or(charges.fast_charger_present)`와 **474건 중 어긋난 건이 0건**이다. 분포에 큰 골이 있다 — 완속 432건이 전부 **0~9.3kW**이고 급속은 **20.8kW**부터다. 임계값 15는 그 골 한가운데다.

**조인하면 877ms, 파생하면 9.6ms다.** `charges`가 485,830행이라 세션마다 LATERAL 조회를 도는 값이 크다.

**2. 모집단에서 축퇴 세션을 빼되 돈 낸 것은 남긴다.** 축퇴 11건은 SoC가 그대로(`95→95`, `35→35`)이고 kWh가 0이거나 null인, 케이블만 꽂았다 뺀 세션이다. 2단계가 `distance > 0`으로 0km 주행을 뺀 것과 같은 규칙이다. 다만 `id=15`는 TeslaMate가 데이터를 통째로 잃었는데 **10,360원은 실제로 낸 돈**이라, `OR cost IS NOT NULL`로 남긴다. 안 남기면 누적 비용이 3,644,562 → 3,634,202로 줄어 실제 지출과 어긋난다.

**3. 평균 전력을 못 내는 세션은 완속으로 본다.** `id=15` 하나뿐이다(`duration_min`이 null). `COALESCE(..., 0) >= 15`가 자연히 완속으로 보내고, 그래야 **`급속 + 완속 = 합계` 불변식**이 선다. 2단계 최종 리뷰가 「온도 카드와 거리 카드의 총합이 다르다」를 지적했던 계열이라 여기서는 맞아떨어지게 한다.

**4. 금액 미입력 35건은 「무료 충전」으로 보인다 — 그러나 서버는 그렇게 부르지 않는다.**

미입력율이 급속 **57%**(24/42) 대 완속 **2.5%**(11/432)로 갈린다. 사용자 확인: 급속은 슈퍼차저 무료 시기, 완속은 데스티네이션 차저라 둘 다 무료다. 실제로 `cost = 0`인 행이 하나도 없어 「무료를 0원으로 적는」 관례가 없다.

**그래도 DB에 기록된 것은 「금액 없음」이지 「0원」이 아니다.** 서버는 `costMissing*`으로 내고 「무료 충전」이라는 해석은 앱이 붙인다 — 신차 기준값·유가 상수를 서버에 두지 않는 것과 같은 계열이고, 나중에 유료 충전을 깜빡 안 적었을 때 서버가 그것을 「무료」로 단정해 버리는 것도 막는다.

**이 값이 없으면 앱의 단가가 틀린다:**

```
잘못:  3,644,562 ÷ 18,197.2           = 200.3 원/kWh
옳게:  3,644,562 ÷ (18,197.2 − 977.0) = 211.6 원/kWh    ← 5.6% 차이
```

분모가 `energyUsed`(벽에서 뽑은 양)인 것은 `ChargeListItem.energyUsedKwh`의 기존 주석이 이미 정해 둔 규칙이다.

---

## 응답 형태

### `GET /tesla/charges/totals`

```json
{ "data": {
  "chargeCount": 474,
  "energyAddedKwh": 17442.0,
  "energyUsedKwh": 18197.2,
  "cost": 3644562,
  "costMissingCount": 35,
  "costMissingEnergyUsedKwh": 977.0,
  "firstChargedAt": "2021-09-03",
  "fast": { "chargeCount": 42,  "energyAddedKwh": 1358.4,  "energyUsedKwh": 1329.0,
            "cost": 143337,  "costMissingCount": 24, "costMissingEnergyUsedKwh": 833.9 },
  "slow": { "chargeCount": 432, "energyAddedKwh": 16083.6, "energyUsedKwh": 16868.2,
            "cost": 3501225, "costMissingCount": 11, "costMissingEnergyUsedKwh": 143.1 }
} }
```

- **파라미터가 없다.** 전 기간이다.
- **`fast + slow = 최상위` 불변식이 선다.** 최상위 중복은 의도된 것이다 — 헤드라인과 내역이다.
- **누적 주행거리를 내지 않는다** — `/tesla/status`의 odometer가 이미 낸다.
- **주유비 대비 절감액을 내지 않는다** — 유가 상수가 필요한데 서버에 두지 않는다.
- `firstChargedAt`은 KST 날짜다. 「언제부터의 누적인가」가 없으면 숫자가 뜻을 잃는다.

### `GET /tesla/charges/{id}/curve`

```json
{ "data": { "samples": [
  { "at": "2026-05-04T13:02:11", "powerKw": 90, "batteryLevel": 42 }
] } }
```

- `charges`의 `date`·`charger_power`·`battery_level` 셋만. 시각순.
- **시각은 KST 그대로.** 경과 분을 서버가 계산하지 않는다 — x축을 무엇으로 할지는 앱이 정한다.
- **샘플을 줄이지 않는다**(완속 1,700개까지). 「어느 점을 버릴지」를 서버가 정하지 않는다.
- 없는 id / 진행 중(`end_date IS NULL`) → **404**. `findDetail`이 이미 같은 규칙이다.
- 샘플이 하나도 없으면 **빈 배열**이다(null이 아니다).

---

### Task 1: 행 타입·리포지토리 인터페이스와 두 SQL

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaChargeRows.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaChargeRepository.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/JdbcTeslaChargeRepository.kt`

**Interfaces:**
- Consumes: 없음
- Produces:
  - `ChargeTotalsRow(chargeCount: Int, energyAddedKwh: BigDecimal?, energyUsedKwh: BigDecimal?, cost: BigDecimal?, costMissingCount: Int, costMissingEnergyUsedKwh: BigDecimal?, firstChargedUtc: LocalDateTime?, fastChargeCount: Int, fastEnergyAddedKwh: BigDecimal?, fastEnergyUsedKwh: BigDecimal?, fastCost: BigDecimal?, fastCostMissingCount: Int, fastCostMissingEnergyUsedKwh: BigDecimal?)`
  - `ChargeCurveSampleRow(dateUtc: LocalDateTime, powerKw: Int?, batteryLevel: Int?)`
  - `TeslaChargeRepository.findTotals(): ChargeTotalsRow`
  - `TeslaChargeRepository.findCurve(id: Long): List<ChargeCurveSampleRow>`
  - `TeslaChargeRepository.existsCompleted(id: Long): Boolean`

**이 태스크에 새 단위 테스트가 없다** — 이 저장소는 SQL을 단위 테스트로 검증하지 않는다(Task 4에서 실 DB로 확인한다).

**왜 급속만 행에 담고 완속은 안 담는가:** 완속 = 합계 − 급속이라 서비스가 뺄셈으로 낸다. SQL이 같은 값을 두 번 세지 않게 하고, **불변식이 코드로 강제된다** — 뺄셈으로 내면 `급속 + 완속 = 합계`가 어긋날 수 없다.

- [ ] **Step 1: 행 타입 둘을 더한다**

`TeslaChargeRows.kt` 맨 끝에 붙인다. 파일 상단은 이미 `java.math.BigDecimal`·`java.time.LocalDateTime`을 import 한다.

```kotlin
/**
 * 전 기간 충전 누적. **한 쿼리가 합계와 급속분을 함께 낸다** — 완속은 서비스가 뺄셈으로 만든다.
 * 그래야 `급속 + 완속 = 합계`가 어긋날 수 없다.
 *
 * **모집단은 `end_date IS NOT NULL AND (charge_energy_added > 0 OR cost IS NOT NULL)`이다.**
 * 케이블만 꽂았다 뺀 축퇴 세션(SoC 그대로, kWh 0)을 빼되, TeslaMate가 데이터를 잃고 금액만
 * 남은 행은 남긴다 — 그 돈은 실제로 나갔다.
 *
 * 합계 필드가 nullable인 것은 `SUM`이 빈 집합에 null을 주기 때문이다. 개수는 `COUNT`라 non-null이다.
 */
data class ChargeTotalsRow(
    val chargeCount: Int,
    val energyAddedKwh: BigDecimal?,
    val energyUsedKwh: BigDecimal?,
    /** `SUM`이 null을 건너뛰므로 **실제로 낸 돈**이다. 미입력분은 여기 없다. */
    val cost: BigDecimal?,
    /**
     * 금액이 비어 있는 건수. 실측(2026-08-18)으로 이 차량은 35건이고 급속에 몰려 있는데,
     * **서버는 그것을 「무료」라고 부르지 않는다** — DB에 기록된 것은 「금액 없음」이지 「0원」이
     * 아니다. 해석은 앱이 붙인다.
     */
    val costMissingCount: Int,
    /**
     * 금액이 빈 세션들의 `charge_energy_used` 합. **앱이 kWh당 단가를 옳게 내는 분모다** —
     * `cost ÷ (energyUsedKwh − costMissingEnergyUsedKwh)`. 이 값이 없으면 단가가 5.6% 낮게 나온다.
     */
    val costMissingEnergyUsedKwh: BigDecimal?,
    val firstChargedUtc: LocalDateTime?,
    val fastChargeCount: Int,
    val fastEnergyAddedKwh: BigDecimal?,
    val fastEnergyUsedKwh: BigDecimal?,
    val fastCost: BigDecimal?,
    val fastCostMissingCount: Int,
    val fastCostMissingEnergyUsedKwh: BigDecimal?,
)

/**
 * 충전 곡선의 샘플 하나. **줄이지 않고 그대로 낸다** — 완속 세션은 1,700개까지 간다.
 * 「어느 점을 버릴지」를 서버가 정하면 화면이 그것을 따라야 한다.
 */
data class ChargeCurveSampleRow(
    val dateUtc: LocalDateTime,
    val powerKw: Int?,
    val batteryLevel: Int?,
)
```

- [ ] **Step 2: 리포지토리 인터페이스에 메서드 셋을 더한다**

`TeslaChargeRepository.kt`의 `findMonthCharges` 아래에 붙인다.

```kotlin
    /**
     * 전 기간 충전 누적. 파라미터가 없다. **집계 쿼리라 행은 항상 온다** — 모집단이 비어도
     * 개수 0과 null 합이 든 행 하나가 온다.
     *
     * 급속 여부는 **`charges`를 조인하지 않고** 세션 평균 전력으로 파생한다 — 실측으로
     * `fast_charger_present`와 474건 전부 일치했고, 조인하면 877ms인 것이 9.6ms가 된다.
     */
    fun findTotals(): ChargeTotalsRow

    /**
     * 세션 하나의 kW 샘플, 시각순. **줄이지 않는다.**
     *
     * 없는 id·진행 중인 세션과 「샘플이 하나도 없는 세션」이 둘 다 빈 리스트라 구분되지 않는다.
     * 404 판정은 `existsCompleted`가 따로 한다.
     */
    fun findCurve(id: Long): List<ChargeCurveSampleRow>

    /** 마감된 충전이 있는지. 곡선의 404 판정에 쓴다 — 진행 중(`end_date IS NULL`)은 false다. */
    fun existsCompleted(id: Long): Boolean
```

- [ ] **Step 3: JDBC 구현 셋을 더한다**

`JdbcTeslaChargeRepository.kt`의 `findMonthCharges` 아래에 붙인다. 필요한 import(`BigDecimal`·`LocalDateTime`)는 이미 있다.

```kotlin
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
```

**`nullableInt`가 이미 이 파일에 있으면 다시 만들지 마라** — 그때는 기존 것을 쓰고 위 마지막 줄을 빼라. `java.sql.ResultSet` import가 없으면 더해야 한다.

`charger_power`·`battery_level`은 `smallint`인데 **nullable이므로 `getObject`로 읽는다.** `rs.getInt`는 SQL NULL에 0을 줘서 「전력 0kW」와 구분되지 않는다.

- [ ] **Step 4: SQL 상수 셋을 더한다**

`companion object` 안, 기존 상수들 아래에 붙인다.

```kotlin
        /**
         * 두 SQL이 공유하는 **모집단 정의**다. 지표별 조건이 아니다 —
         * 「무엇을 한 건의 충전으로 셀 것인가」이고, 두 응답의 건수가 서로 말이 되려면 같아야 한다.
         *
         * `charge_energy_added > 0 OR cost IS NOT NULL`인 이유: 케이블만 꽂았다 뺀 축퇴 세션은
         * SoC가 그대로이고 kWh가 0이라 누적에 낄 이유가 없다(실측 2026-08-18로 11건).
         * 다만 그중 `id=15`는 TeslaMate가 데이터를 통째로 잃었는데 **10,360원은 실제로 낸 돈**이라
         * 남긴다 — 빼면 누적 비용이 실제 지출과 어긋난다.
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
```

- [ ] **Step 5: 컴파일과 기존 테스트가 초록인지 확인한다**

Run: `./gradlew spotlessApply :daily-record:test`
Expected: BUILD SUCCESSFUL. 새 테스트는 없고 기존 테스트가 그대로 통과해야 한다. `TeslaChargeRepository`를 구현한 클래스가 `JdbcTeslaChargeRepository` 하나뿐인지 확인해라.

- [ ] **Step 6: 커밋**

```bash
git add apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaChargeRows.kt \
        apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaChargeRepository.kt \
        apps/daily-record/src/main/kotlin/com/toy/backend/tesla/JdbcTeslaChargeRepository.kt
git commit -m "feat: 충전 누적과 곡선 샘플을 집계한다"
```

---

### Task 2: 응답 DTO와 서비스 — 완속을 뺄셈으로 만든다

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaChargeDtos.kt`
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaChargeService.kt`
- Test: `apps/daily-record/src/test/kotlin/com/toy/backend/tesla/TeslaChargeServiceTest.kt`

**Interfaces:**
- Consumes: Task 1의 `ChargeTotalsRow`·`ChargeCurveSampleRow`와 세 리포지토리 메서드
- Produces:
  - `TeslaChargeTotalsResponse(chargeCount, energyAddedKwh, energyUsedKwh, cost, costMissingCount, costMissingEnergyUsedKwh, firstChargedAt, fast, slow)`
  - `ChargeTotalsBreakdown(chargeCount: Int, energyAddedKwh: BigDecimal?, energyUsedKwh: BigDecimal?, cost: BigDecimal?, costMissingCount: Int, costMissingEnergyUsedKwh: BigDecimal?)`
  - `TeslaChargeCurveResponse(samples: List<ChargeCurveSample>)`
  - `ChargeCurveSample(at: LocalDateTime, powerKw: Int?, batteryLevel: Int?)`
  - `TeslaChargeService.totals(): TeslaChargeTotalsResponse`
  - `TeslaChargeService.curve(id: Long): TeslaChargeCurveResponse`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`TeslaChargeServiceTest.kt`의 마지막 `Given` 블록 뒤, 파일을 닫는 `})` 앞에 붙인다. 기존 파일이 이미 import 하는 것 외에 필요한 것이 있으면 더해라(`java.time.LocalDate`가 필요할 수 있다).

```kotlin
        // 서비스가 하는 일은 완속 = 합계 − 급속 뺄셈과 행→DTO 변환뿐이다. 집계는 SQL이 한다.
        Given("충전 누적을 조회할 때") {
            every { repository.findTotals() } returns
                ChargeTotalsRow(
                    chargeCount = 474,
                    energyAddedKwh = BigDecimal("17442.0"),
                    energyUsedKwh = BigDecimal("18197.2"),
                    cost = BigDecimal("3644562"),
                    costMissingCount = 35,
                    costMissingEnergyUsedKwh = BigDecimal("977.0"),
                    firstChargedUtc = LocalDateTime.of(2021, 9, 2, 15, 0),
                    fastChargeCount = 42,
                    fastEnergyAddedKwh = BigDecimal("1358.4"),
                    fastEnergyUsedKwh = BigDecimal("1329.0"),
                    fastCost = BigDecimal("143337"),
                    fastCostMissingCount = 24,
                    fastCostMissingEnergyUsedKwh = BigDecimal("833.9"),
                )

            val response = service.totals()

            Then("합계는 그대로 나간다") {
                response.chargeCount shouldBe 474
                response.energyAddedKwh shouldBe BigDecimal("17442.0")
                response.cost shouldBe BigDecimal("3644562")
                response.costMissingCount shouldBe 35
                response.costMissingEnergyUsedKwh shouldBe BigDecimal("977.0")
            }

            Then("급속은 행에 온 값 그대로다") {
                response.fast.chargeCount shouldBe 42
                response.fast.energyUsedKwh shouldBe BigDecimal("1329.0")
                response.fast.costMissingCount shouldBe 24
            }

            // 완속을 SQL로 따로 세지 않고 뺄셈으로 만든다 — 그래야 불변식이 어긋날 수 없다.
            Then("완속은 합계에서 급속을 뺀 값이다") {
                response.slow.chargeCount shouldBe 432
                response.slow.energyAddedKwh shouldBe BigDecimal("16083.6")
                response.slow.energyUsedKwh shouldBe BigDecimal("16868.2")
                response.slow.cost shouldBe BigDecimal("3501225")
                response.slow.costMissingCount shouldBe 11
                response.slow.costMissingEnergyUsedKwh shouldBe BigDecimal("143.1")
            }

            // 2단계 리뷰가 「온도 카드와 거리 카드의 총합이 다르다」를 지적했던 계열이다.
            Then("급속 + 완속 = 합계 불변식이 선다") {
                response.fast.chargeCount + response.slow.chargeCount shouldBe response.chargeCount
                response.fast.cost!! + response.slow.cost!! shouldBe response.cost
                response.fast.costMissingCount + response.slow.costMissingCount shouldBe response.costMissingCount
            }

            // UTC 15:00 = KST 다음날 00:00. 날짜만 낸다.
            Then("firstChargedAt이 KST 날짜다") {
                response.firstChargedAt shouldBe LocalDate.of(2021, 9, 3)
            }
        }

        Given("충전이 하나도 없어 합이 전부 null일 때") {
            every { repository.findTotals() } returns
                ChargeTotalsRow(
                    chargeCount = 0,
                    energyAddedKwh = null,
                    energyUsedKwh = null,
                    cost = null,
                    costMissingCount = 0,
                    costMissingEnergyUsedKwh = null,
                    firstChargedUtc = null,
                    fastChargeCount = 0,
                    fastEnergyAddedKwh = null,
                    fastEnergyUsedKwh = null,
                    fastCost = null,
                    fastCostMissingCount = 0,
                    fastCostMissingEnergyUsedKwh = null,
                )

            val response = service.totals()

            // 0으로 채우지 않는다 — 「기록이 없다」와 「0이다」가 구분돼야 한다.
            Then("합은 null로 나가고 개수는 0이다") {
                response.energyAddedKwh shouldBe null
                response.cost shouldBe null
                response.firstChargedAt shouldBe null
                response.chargeCount shouldBe 0
            }

            // null − null은 null이지 0이 아니다.
            Then("완속 뺄셈도 null을 지킨다") {
                response.slow.chargeCount shouldBe 0
                response.slow.energyAddedKwh shouldBe null
                response.slow.cost shouldBe null
            }
        }

        Given("급속만 있고 완속이 없을 때") {
            every { repository.findTotals() } returns
                ChargeTotalsRow(
                    chargeCount = 2,
                    energyAddedKwh = BigDecimal("80.0"),
                    energyUsedKwh = BigDecimal("82.0"),
                    cost = BigDecimal("24000"),
                    costMissingCount = 0,
                    costMissingEnergyUsedKwh = null,
                    firstChargedUtc = LocalDateTime.of(2026, 1, 1, 0, 0),
                    fastChargeCount = 2,
                    fastEnergyAddedKwh = BigDecimal("80.0"),
                    fastEnergyUsedKwh = BigDecimal("82.0"),
                    fastCost = BigDecimal("24000"),
                    fastCostMissingCount = 0,
                    fastCostMissingEnergyUsedKwh = null,
                )

            val response = service.totals()

            // 뺄셈 결과가 0인 것과 null인 것을 섞지 않는다.
            Then("완속은 개수 0이고 합은 0이다") {
                response.slow.chargeCount shouldBe 0
                response.slow.energyAddedKwh shouldBe BigDecimal("0.0")
                response.slow.cost shouldBe BigDecimal("0")
            }
        }

        Given("충전 곡선을 조회할 때") {
            every { repository.existsCompleted(490) } returns true
            every { repository.findCurve(490) } returns
                listOf(
                    ChargeCurveSampleRow(LocalDateTime.of(2026, 5, 4, 4, 2, 11), 90, 42),
                    ChargeCurveSampleRow(LocalDateTime.of(2026, 5, 4, 4, 3, 11), 88, 43),
                )

            val response = service.curve(490)

            // UTC 04:02 = KST 13:02. 경과 분은 서버가 내지 않는다 — x축은 앱이 정한다.
            Then("샘플이 KST 시각으로 나간다") {
                response.samples.size shouldBe 2
                response.samples.first().at shouldBe LocalDateTime.of(2026, 5, 4, 13, 2, 11)
                response.samples.first().powerKw shouldBe 90
                response.samples.first().batteryLevel shouldBe 42
            }
        }

        // 「샘플이 없는 세션」과 「없는 세션」은 다르다. 앞은 빈 배열, 뒤는 404다.
        Given("세션은 있는데 샘플이 하나도 없을 때") {
            every { repository.existsCompleted(15) } returns true
            every { repository.findCurve(15) } returns emptyList()

            val response = service.curve(15)

            Then("samples가 빈 배열이다") {
                response.samples shouldBe emptyList()
            }
        }

        Given("없는 id로 곡선을 조회할 때") {
            every { repository.existsCompleted(9999) } returns false

            Then("404다") {
                shouldThrow<CustomException> { service.curve(9999) }
                    .errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
            }

            // 존재 확인이 먼저다 — 없는 것으로 판정났으면 샘플을 읽을 이유가 없다.
            Then("샘플 조회를 하지 않는다") {
                shouldThrow<CustomException> { service.curve(9999) }
                verify(exactly = 0) { repository.findCurve(9999) }
            }
        }
```

`verify`가 파일에 import 돼 있지 않으면 `import io.mockk.verify`를 더해라.

- [ ] **Step 2: 실패하는지 돌린다**

Run: `./gradlew :daily-record:test --tests '*TeslaChargeServiceTest*'`
Expected: 컴파일 실패 — `Unresolved reference: totals` / `curve`. 아직 서비스에 메서드가 없으므로 이 실패가 맞다.

- [ ] **Step 3: 응답 DTO를 더한다**

`TeslaChargeDtos.kt` 맨 끝에 붙인다. `java.time.LocalDate` import를 더해야 한다.

```kotlin
/**
 * 전 기간 충전 누적. 파라미터가 없다.
 *
 * **`fast + slow = 최상위` 불변식이 선다.** 최상위 중복은 의도된 것이다 — 헤드라인과 내역이다.
 * 서비스가 완속을 뺄셈으로 만들기 때문에 이 불변식이 코드로 강제된다.
 *
 * **누적 주행거리를 싣지 않는다** — `/tesla/status`의 odometer가 이미 낸다.
 * **주유비 대비 절감액을 싣지 않는다** — 유가 상수가 필요한데 서버에 두지 않는다(신차 기준선과 같은 이유).
 */
data class TeslaChargeTotalsResponse(
    val chargeCount: Int,
    val energyAddedKwh: BigDecimal?,
    /** 벽에서 뽑아쓴 양. **kWh당 단가의 분모는 이쪽이다** — `ChargeListItem`이 정해 둔 규칙이다. */
    val energyUsedKwh: BigDecimal?,
    /** **실제로 낸 돈이다.** `SUM`이 null을 건너뛰므로 금액이 빈 세션은 여기 없다. */
    val cost: BigDecimal?,
    /**
     * 금액이 비어 있는 건수. **`/tesla/charges/missing-cost`의 `totalCount`와 다른 수다** —
     * 그쪽은 최근 한 달 창이고 이쪽은 전 기간이다. 앱이 두 값을 같은 배지로 쓰면 어긋난다.
     *
     * **서버는 이것을 「무료 충전」이라고 부르지 않는다.** DB에 기록된 것은 「금액 없음」이지
     * 「0원」이 아니다. 해석은 앱이 붙인다 — 신차 기준값·유가 상수를 서버에 두지 않는 것과 같은 계열이고,
     * 유료 충전을 깜빡 안 적었을 때 서버가 그것을 「무료」로 단정해 버리는 것도 막는다.
     */
    val costMissingCount: Int,
    /**
     * 금액이 빈 세션들의 `energyUsedKwh` 합. **앱이 단가를 옳게 내는 분모다:**
     * `cost ÷ (energyUsedKwh − costMissingEnergyUsedKwh)`.
     *
     * 이 값을 빼지 않으면 단가가 낮게 나온다 — 실측(2026-08-18)으로 200.3 vs 211.6원/kWh, 5.6% 차이다.
     */
    val costMissingEnergyUsedKwh: BigDecimal?,
    /** 「언제부터의 누적인가」. KST 날짜다. 기록이 없으면 null이다. */
    val firstChargedAt: LocalDate?,
    /** 세션 평균 전력 15kW 이상. 충전기 종류가 아니라 **그 세션의 결과**로 가른 값이다. */
    val fast: ChargeTotalsBreakdown,
    val slow: ChargeTotalsBreakdown,
)

/** 최상위와 같은 다섯 값. 합이 null인 것은 그 구간에 기록이 없다는 뜻이지 0이 아니다. */
data class ChargeTotalsBreakdown(
    val chargeCount: Int,
    val energyAddedKwh: BigDecimal?,
    val energyUsedKwh: BigDecimal?,
    val cost: BigDecimal?,
    val costMissingCount: Int,
    val costMissingEnergyUsedKwh: BigDecimal?,
)

/**
 * 한 세션의 kW 곡선. **줄이지 않고 그대로 낸다** — 완속은 1,700개까지 간다.
 *
 * 지난 기록이라 「실시간을 내지 않는다」는 방침과 부딪히지 않는다.
 */
data class TeslaChargeCurveResponse(
    /** 시각순. 샘플이 하나도 없으면 **빈 배열이다**(null이 아니다). */
    val samples: List<ChargeCurveSample>,
)

data class ChargeCurveSample(
    /** KST. **경과 분을 서버가 내지 않는다** — x축을 무엇으로 할지는 앱이 정한다. */
    val at: LocalDateTime,
    /** null일 수 있다. **0kW와 구분된다.** */
    val powerKw: Int?,
    val batteryLevel: Int?,
)
```

- [ ] **Step 4: 서비스 메서드를 더한다**

`TeslaChargeService.kt`의 `missingCost` 아래에 붙인다. `java.math.BigDecimal`·`java.time.LocalDate` import를 더해야 할 수 있다.

```kotlin
    /**
     * 쿼리 한 번이 전부다. **완속을 SQL로 따로 세지 않고 합계에서 급속을 뺀다** —
     * 그래야 `급속 + 완속 = 합계`가 어긋날 길이 없고, `charging_processes`를 두 번 훑지도 않는다.
     */
    fun totals(): TeslaChargeTotalsResponse {
        val row = repository.findTotals()
        return TeslaChargeTotalsResponse(
            chargeCount = row.chargeCount,
            energyAddedKwh = row.energyAddedKwh,
            energyUsedKwh = row.energyUsedKwh,
            cost = row.cost,
            costMissingCount = row.costMissingCount,
            costMissingEnergyUsedKwh = row.costMissingEnergyUsedKwh,
            firstChargedAt = row.firstChargedUtc?.let { TeslaTime.toKst(it).toLocalDate() },
            fast =
                ChargeTotalsBreakdown(
                    chargeCount = row.fastChargeCount,
                    energyAddedKwh = row.fastEnergyAddedKwh,
                    energyUsedKwh = row.fastEnergyUsedKwh,
                    cost = row.fastCost,
                    costMissingCount = row.fastCostMissingCount,
                    costMissingEnergyUsedKwh = row.fastCostMissingEnergyUsedKwh,
                ),
            slow =
                ChargeTotalsBreakdown(
                    chargeCount = row.chargeCount - row.fastChargeCount,
                    energyAddedKwh = minus(row.energyAddedKwh, row.fastEnergyAddedKwh),
                    energyUsedKwh = minus(row.energyUsedKwh, row.fastEnergyUsedKwh),
                    cost = minus(row.cost, row.fastCost),
                    costMissingCount = row.costMissingCount - row.fastCostMissingCount,
                    costMissingEnergyUsedKwh = minus(row.costMissingEnergyUsedKwh, row.fastCostMissingEnergyUsedKwh),
                ),
        )
    }

    /**
     * **존재 확인이 먼저다.** `findCurve`가 빈 리스트를 주는 이유가 둘이라
     * (「없는 id·진행 중」과 「샘플이 없는 세션」) 그것만으로는 404를 가릴 수 없다.
     */
    fun curve(id: Long): TeslaChargeCurveResponse {
        if (!repository.existsCompleted(id)) {
            throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        }
        return TeslaChargeCurveResponse(
            samples =
                repository.findCurve(id).map {
                    ChargeCurveSample(
                        at = TeslaTime.toKst(it.dateUtc),
                        powerKw = it.powerKw,
                        batteryLevel = it.batteryLevel,
                    )
                },
        )
    }

    /**
     * 합계 − 급속. **합계가 null이면 결과도 null이다** — 그 구간에 기록이 없다는 뜻이지 0이 아니다.
     * 급속만 null인 경우는 「합계는 있는데 급속이 없다」이므로 합계를 그대로 돌려준다.
     */
    private fun minus(
        total: BigDecimal?,
        fast: BigDecimal?,
    ): BigDecimal? = total?.subtract(fast ?: BigDecimal.ZERO)
```

- [ ] **Step 5: 통과하는지 돌린다**

Run: `./gradlew spotlessApply :daily-record:test --tests '*TeslaChargeServiceTest*'`
Expected: PASS.

`BigDecimal("17442.0") - BigDecimal("1358.4") = BigDecimal("16083.6")`처럼 scale이 유지되는지가 테스트가 잡는 지점이다. `subtract`는 두 피연산자 중 큰 scale을 따르므로 소수 한 자리가 유지된다. `cost`는 둘 다 scale 0이라 `BigDecimal("3501225")`가 나온다.

- [ ] **Step 6: 전체 테스트를 한 번 돌린다**

Run: `./gradlew :daily-record:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: 커밋**

```bash
git add apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaChargeDtos.kt \
        apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaChargeService.kt \
        apps/daily-record/src/test/kotlin/com/toy/backend/tesla/TeslaChargeServiceTest.kt
git commit -m "feat: 충전 누적과 곡선을 응답으로 낸다"
```

---

### Task 3: 엔드포인트 배선

**Files:**
- Modify: `apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaChargeController.kt`

**Interfaces:**
- Consumes: `TeslaChargeService.totals()`·`curve(id)`
- Produces: `GET /tesla/charges/totals`, `GET /tesla/charges/{id}/curve`

- [ ] **Step 1: `@Tag` 설명을 갱신한다**

지금은 `description = "TeslaMate 충전 내역 API"`다. 누적과 곡선이 드러나게 고친다.

- [ ] **Step 2: 매핑 둘을 더한다**

**`totals`를 `@GetMapping("/{id}")`보다 위에 둔다.** Spring이 리터럴 경로를 우선하므로 동작은 순서와 무관하지만, `missing-cost`가 이미 `/{id}` 위에 있으니 그 배치를 따른다.

`missingCost` 아래, `detail` 위에 붙인다:

```kotlin
    /**
     * 전 기간 누적. **파라미터가 없다.**
     *
     * `missing-cost`와 마찬가지로 `/{id}`보다 위에 둔다 — Spring은 리터럴 경로를 먼저 맞추므로
     * 동작은 순서와 무관하지만, 읽는 사람이 `totals`를 id로 오해하지 않게 한다.
     */
    @GetMapping("/totals")
    @Operation(summary = "충전 누적 — 전 기간 kWh·비용, 급속/완속 구분")
    fun totals(): ResponseEntity<DataResponseBody<TeslaChargeTotalsResponse>> = ResponseEntity.ok(DataResponseBody(service.totals()))
```

`detail` 아래에 붙인다:

```kotlin
    /**
     * 그 세션의 kW 곡선. **샘플을 줄이지 않는다** — 완속은 1,700개까지 간다.
     * 지난 기록이라 「실시간을 내지 않는다」는 방침과 부딪히지 않는다.
     */
    @GetMapping("/{id}/curve")
    @Operation(summary = "충전 곡선 — 그 세션의 kW·배터리 샘플")
    fun curve(
        @PathVariable id: Long,
    ): ResponseEntity<DataResponseBody<TeslaChargeCurveResponse>> = ResponseEntity.ok(DataResponseBody(service.curve(id)))
```

- [ ] **Step 3: 전체 빌드가 초록인지 확인한다**

Run: `./gradlew spotlessApply build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: 새 심볼이 응답까지 실렸는지 대 본다** (AGENTS.md 「커밋 전」 검사)

Run:

```bash
for name in costMissingEnergyUsedKwh costMissingCount firstChargedAt chargeCount powerKw batteryLevel; do
  echo "=== $name ==="; grep -rln "$name" --include='*.kt' .
done
```

Expected: 각 목록에 **`TeslaChargeDtos.kt`가 반드시 있어야 한다** — 없으면 그 값은 앱까지 가지 않는다.

- [ ] **Step 5: 커밋**

```bash
git add apps/daily-record/src/main/kotlin/com/toy/backend/tesla/TeslaChargeController.kt
git commit -m "feat: 충전 누적과 곡선을 엔드포인트로 연다"
```

---

### Task 4: 실 DB에서 두 SQL을 눈으로 확인한다

단위 테스트는 리포지토리를 목으로 대체하므로 **SQL 자체는 검증되지 않는다.** 접속은 Docker psql로 한다(로컬에 `psql`이 없다). 접속 정보는 컨트롤러가 준다.

**Files:** 없음 (읽기만 한다)

**Interfaces:**
- Consumes: Task 1의 `TOTALS_SQL`·`CURVE_SQL`·`EXISTS_COMPLETED_SQL`
- Produces: 실측 숫자 — Task 5·6이 쓴다

- [ ] **Step 1: `TOTALS_SQL`을 코드에서 그대로 가져와 돌린다**

`JdbcTeslaChargeRepository.kt`의 상수 원문(`$CHARGE_POPULATION`·`$FAST_CHARGE` 보간까지 풀어서)을 그대로 돌려라. **브리프에 적힌 것을 손으로 옮겨 적지 말고 실제 코드에 있는 것을 돌려야 의미가 있다.**

Expected(2026-08-18 기준값):

| 필드 | 값 |
|---|---|
| `charge_count` | 474 |
| `energy_added_kwh` / `energy_used_kwh` | 17442.0 / 18197.2 |
| `cost` | 3644562 |
| `cost_missing_count` / `cost_missing_energy_used_kwh` | 35 / 977.0 |
| `first_charged_at` | 2021-09-02 또는 09-03 (UTC 값이라 KST 변환은 서비스가 한다) |
| `fast_charge_count` | 42 |
| `fast_energy_added_kwh` / `fast_energy_used_kwh` | 1358.4 / 1329.0 |
| `fast_cost` | 143337 |
| `fast_cost_missing_count` / `fast_cost_missing_energy_used_kwh` | 24 / 833.9 |

- [ ] **Step 2: 불변식이 실제로 서는지 뺄셈으로 확인한다**

Step 1 결과로 손으로 검산해라: `474 − 42 = 432`, `17442.0 − 1358.4 = 16083.6`, `3644562 − 143337 = 3501225`, `35 − 24 = 11`, `977.0 − 833.9 = 143.1`.

Expected: 다섯 개 전부 맞는다. 하나라도 틀리면 **그것을 보고서 맨 위에 크게 적어라** — 완속을 뺄셈으로 만드는 설계의 전제가 깨진 것이다.

- [ ] **Step 3: 급속 파생이 `fast_charger_present`와 여전히 일치하는지 대조한다**

```sql
WITH x AS (
  SELECT cp.id,
         COALESCE(cp.charge_energy_added / NULLIF(cp.duration_min,0) * 60, 0) >= 15 AS derived,
         (SELECT bool_or(c.fast_charger_present) FROM charges c WHERE c.charging_process_id = cp.id) AS actual
    FROM charging_processes cp
   WHERE cp.end_date IS NOT NULL
     AND (cp.charge_energy_added > 0 OR cp.cost IS NOT NULL)
)
SELECT count(*) AS 전체,
       count(*) FILTER (WHERE derived = COALESCE(actual, false)) AS 일치,
       count(*) FILTER (WHERE derived <> COALESCE(actual, false)) AS 불일치
  FROM x
```

Expected: 불일치가 **0**이거나, 있어도 그 id를 적어라. 이 파생이 `charges` 조인을 대신하는 근거다.

- [ ] **Step 4: 임계값 15kW가 여전히 골 한가운데인지 본다**

```sql
SELECT round(min(avg_kw),1) AS 완속_최대_아래, round(max(avg_kw),1) AS 값
  FROM (SELECT charge_energy_added / NULLIF(duration_min,0) * 60 AS avg_kw
          FROM charging_processes
         WHERE end_date IS NOT NULL AND charge_energy_added > 0) t
 WHERE avg_kw < 15
UNION ALL
SELECT round(min(avg_kw),1), round(max(avg_kw),1)
  FROM (SELECT charge_energy_added / NULLIF(duration_min,0) * 60 AS avg_kw
          FROM charging_processes
         WHERE end_date IS NOT NULL AND charge_energy_added > 0) t
 WHERE avg_kw >= 15
```

Expected: 첫 행의 최대가 10 미만이고 둘째 행의 최소가 20 이상이다 — 15 양쪽에 여유가 있어야 한다. 좁아졌으면 보고서에 적어라.

- [ ] **Step 5: `CURVE_SQL`을 급속·완속 세션 각각에 돌린다**

급속 한 건(예: `id = 468`)과 완속 한 건(예: `id = 490`)에 돌려라.

Expected: 급속은 250~360행에 `charger_power`가 수십~수백까지 올랐다 내려오는 곡선, 완속은 700~1,700행에 7 근처로 평평하다. `date`가 오름차순이다.

- [ ] **Step 6: `EXISTS_COMPLETED_SQL`을 세 경우에 돌린다**

없는 id(예: 999999), 마감된 id(예: 490), 그리고 **샘플이 없는 세션 `id = 15`**에 돌려라.

Expected: 999999 → false, 490 → true, **15 → true**. `id=15`가 true여야 「세션은 있는데 샘플이 없다」가 빈 배열로 나가고 404가 되지 않는다.

- [ ] **Step 7: 앱을 띄워 엔드포인트를 부른다 — 안 되면 안 된 이유를 적는다**

1·2단계에서 이 단계를 **못 했다.** 로컬에 `daily-record` DB·스키마는 있으나 로그인 계정 비밀번호가 없어 토큰을 못 받았다. 이번에도 같을 가능성이 높다.

시도는 하되 **환경을 새로 만들지 마라** — DB 생성·설정 파일 수정·인증 우회·계정 생성 전부 금지. 안 되면 「실행 못 함」으로 적고 무엇이 없어서 못 했는지를 구체적으로 적어라. **이 한 단계 때문에 태스크를 BLOCKED로 내지 마라.**

- [ ] **Step 8: 실측 결과를 정리한다**

커밋할 코드는 없다. 위 단계의 실제 숫자를 적어 Task 5·6으로 넘긴다.

---

### Task 5: 백엔드 설계 문서 3단계 절 확정 + changelog

설계 문서의 3단계는 **한 문단 스케치**다(1·2단계는 상세 스펙이 있다). 이 계획이 확정한 내용으로 채운다.

**Files:**
- Modify: `docs/superpowers/specs/2026-08-17-tesla-battery-health-design.md` (**3단계 절만**)
- Create: `docs/changelog/2026-08-18-tesla-charge-totals-curve.md`

**Interfaces:**
- Consumes: Task 4의 실측 숫자
- Produces: 없음

- [ ] **Step 1: 설계 문서의 3단계 절을 채운다**

지금 `# 3단계 — 누적 합계·충전 곡선` 절은 항목 두 개짜리 스케치다. 1·2단계 절과 같은 밀도로 채워라:

- 두 엔드포인트의 경로와 응답 형태(위 「응답 형태」 절 그대로)
- 모집단 정의(`charge_energy_added > 0 OR cost IS NOT NULL`)와 그 이유, `id=15` 사례
- 급속 파생(평균 전력 ≥ 15kW)과 **`charges`를 조인하지 않는 이유**(877ms vs 9.6ms), 임계값이 안전한 근거(골)
- **`costMissing*`을 「무료」라고 부르지 않는 이유**와 그것이 앱의 단가 분모인 것
- `급속 + 완속 = 합계` 불변식과 완속을 뺄셈으로 만드는 이유
- 곡선을 줄이지 않는 이유, 404 판정을 `existsCompleted`가 따로 하는 이유
- **2026-08-18 실측** 숫자표

**「보류」 절도 갱신해라 — 판정이 나왔다.** `positions.drive_id` 인덱스가 **있다**(`positions_drive_id_date_timestamp_minmax_multi_ops_index`, BRIN). 실측으로 최근 주행 **11.7ms**, 오래된 주행 **20ms**다. 설계가 걱정한 「주행 하나 열 때마다 3,000만 행 훑기」는 일어나지 않는다 — `drive_id`가 append 순서와 강하게 상관돼 BRIN이 페이지 범위를 건너뛴다.

**다만 접는 대신 새 선행 조건이 생겼다:** 주행 하나에 **평균 4,281 샘플·최대 14,386 샘플·km당 478개**다. 다운샘플링 방식(간격·개수·알고리즘)을 정하지 않고는 낼 수 없다. 「인덱스가 있으면 설계하고 없으면 접는다」는 문장을 이 판정으로 바꿔 적어라.

**1·2단계 절은 건드리지 마라.**

- [ ] **Step 2: 커밋**

```bash
git add docs/superpowers/specs/2026-08-17-tesla-battery-health-design.md
git commit -m "docs: 3단계 설계를 확정하고 보류 판정을 적는다"
```

- [ ] **Step 3: changelog를 쓴다**

`docs/changelog/2026-08-18-tesla-charge-totals-curve.md`. 형식은 `docs/changelog/2026-08-17-tesla-drive-insights.md`를 따른다 — 이 저장소는 **왜**를 적는 곳이다.

담아야 하는 것:

- 두 엔드포인트가 무엇을 내는지
- **모집단 정의와 `id=15` 예외** — 「에너지가 들어갔거나, 돈을 냈거나」
- **`charges`를 조인하지 않은 것**(877ms → 9.6ms)과 파생이 `fast_charger_present`와 전부 일치한 것
- **`costMissing*`을 「무료」라고 부르지 않은 이유**와 단가 분모(200.3 vs 211.6원/kWh)
- 급속 미입력율 57% vs 완속 2.5%라는 실측
- **`급속 + 완속 = 합계` 불변식**과 완속을 뺄셈으로 만든 이유 — 2단계 리뷰가 「두 카드의 총합이 다르다」를 지적했던 계열이다
- 곡선을 줄이지 않은 이유
- 404 판정이 왜 따로 필요한지(빈 리스트의 두 가지 뜻)
- Task 4의 실측 숫자
- **보류 판정** — 인덱스는 풀렸고 다운샘플링이 새 선행 조건이다
- 이번에 하지 않은 것: 주유비 절감액(유가 상수), 경로·속도 샘플
- Step 7(엔드포인트 실호출)을 했는지, 못 했으면 무엇이 없어서 못 했는지

- [ ] **Step 4: 커밋**

```bash
git add docs/changelog/2026-08-18-tesla-charge-totals-curve.md
git commit -m "docs: 충전 누적과 곡선을 changelog에 적는다"
```

---

### Task 6: 앱 저장소 스펙 갱신 (`../woori-haru`)

**다른 저장소다.** `/Users/youngminmoon/Documents/moonjm/woori-haru`에서 작업하고, 거기서 따로 커밋한다.

**Files:**
- Modify: `../woori-haru/docs/superpowers/specs/2026-08-17-vehicle-health-dashboard-design.md`

**Interfaces:**
- Consumes: Task 4의 실측 숫자, 이 계획의 응답 형태
- Produces: 없음

**이 태스크가 고치는 것은 셋이다. 셋 다 해라.**

- [ ] **Step 1: 3단계 절의 서버 API를 확정한다**

지금 `# 3단계 — 곁가지` 절은 「누적 스탯 타일 2×2」와 「충전 곡선 — 서버에 세션별 샘플 API가 하나 는다」 수준이다. 확정된 두 엔드포인트로 채워라:

- `GET /tesla/charges/totals`의 응답 형태(위 「응답 형태」 절 그대로)와, 앱이 알아야 할 것:
  - **`costMissingCount`는 「무료 충전」으로 읽을 수 있지만 서버가 그렇게 부르지 않는다.** 그 라벨은 앱이 붙인다.
  - **`/tesla/charges/missing-cost`의 `totalCount`와 다른 수다** — 그쪽은 최근 한 달, 이쪽은 전 기간이다. **배지에 섞어 쓰면 어긋난다.**
  - **단가를 낼 때 `costMissingEnergyUsedKwh`를 분모에서 빼야 한다:** `cost ÷ (energyUsedKwh − costMissingEnergyUsedKwh)`. 안 빼면 5.6% 낮게 나온다(200.3 vs 211.6원/kWh).
  - `fast + slow = 최상위` 불변식이 선다.
  - **누적 주행거리는 여기 없다** — `/tesla/status`의 odometer를 그대로 쓴다.
  - **주유비 대비 절감액도 없다** — 유가 상수는 앱이 정한다. 스펙이 「상수를 어디 둘지는 그때 정한다」고 적어 둔 그 자리다.
- `GET /tesla/charges/{id}/curve`의 응답 형태와:
  - **샘플이 줄어들지 않고 온다** — 급속 250~360개, **완속 700~1,700개**다. 앱이 그릴 때 다운샘플링을 하든 그대로 그리든 정해야 한다.
  - 시각은 KST이고 **경과 분은 없다** — x축을 무엇으로 할지는 앱이 정한다.
  - 없는 id·진행 중은 404, **샘플이 없는 세션은 빈 배열**이다. 둘을 다르게 그려야 한다.

「누적 스탯 타일 2×2」의 네 칸도 실제 값에 맞게 다시 적어라 — 절감액은 유가 상수가 없어 아직 못 내므로, 그 칸을 무엇으로 할지(급속/완속 단가 등) 열어 두거나 3칸으로 줄이는 선택지를 적어라.

- [ ] **Step 2: 1단계 절의 틀린 예측을 정정한다**

`## 서버 API` 절에 이 문장이 있다:

> `capacityKwh`는 자주 null이다(ΔSoC 40%p 이상 충전이 몇 달에 한 번이다). 0으로 읽지 않는다.

**실측(2026-08-17)과 다르다.** 이 차량의 60개월 전 구간(2021-09~2026-08)에서 `capacityKwh`가 null인 달이 **하나도 없었다.** 누적으로도 range 표본 384건 대 capacity 표본 329건으로 14.3% 차이뿐이다.

「0으로 읽지 않는다」는 그대로 두되(null 처리는 여전히 옳은 방어다), **「자주 null이다」를 실측에 맞게 고치고 날짜를 명시해라.** null이 흔하다는 전제로 화면을 설계하면 안 된다는 것을 적어라.

같은 절의 **표본 조건도 실제와 다르다.** 백엔드 SQL은 두 지표를 하나의 공통 WHERE에 얹어서, **용량 표본도 `end_battery_level >= 80`과 `end_rated_range_km IS NOT NULL`을 함께 통과해야 한다.** 스펙의 「ΔSoC 40%p 이상」만으로는 부족하다 — 20%→70% 같은 충전은 표만 보면 유효하지만 실제로는 세지 않는다.

- [ ] **Step 3: 2단계 절에 실측을 반영한다**

셋이다.

- **「지오펜스가 하나도 없다 → 카드를 통째로 감춘다」가 지금 실제 상황이다.** `geofences`가 0행이라 `places`는 **항상 빈 배열**로 온다. 엣지 케이스가 아니라 기본 상태라는 것을 적어라. TeslaMate에 지오펜스를 등록하면 살아난다.
- **`rated range 소모가 0 이하인 주행」의 근거가 틀렸다.** 스펙은 「내리막 회생, 주차 중 보정」이라고 적었는데, 실측은 **447건 중 431건이 차이가 정확히 0**(평균 0.16km, 최대 3.9km)이었다 — 지배적인 것은 **주행가능거리 표시가 1km 단위로 움직이지 않을 만큼 짧은 주행**, 즉 반올림이다. 제외한다는 결론은 그대로다.
- **「전비 카드에 N건 기준을 함께 적는다」가 실제로 필요하다.** 최근 12개월 959건 중 온도 버킷에 들어가는 것은 **939건**이다(20건 이탈). 거리 분포·히트맵은 959건이라 **두 카드의 총합이 다르다** — 앱이 「12개월 N건」을 어느 카드에서 뽑느냐에 따라 숫자가 달라진다는 것을 적어라.

`cars.efficiency`가 **0.1367 kWh/km**로 확인된 것도 적어라(스펙이 「TeslaMate가 차량별로 저장하는 kWh/km 계수다」라고만 적어 둔 자리다).

- [ ] **Step 4: 보류 절의 판정을 적는다**

스펙은 「확인해서 인덱스가 있으면 그때 4단계로 설계한다. 없으면 접는다」로 끝난다. **답이 나왔다:**

- 인덱스가 **있다** — `positions_drive_id_date_timestamp_minmax_multi_ops_index`(BRIN).
- 실측 **11.7ms**(최근 주행)·**20ms**(오래된 주행). 「주행 하나 열 때마다 3,000만 행 훑기」는 일어나지 않는다 — `drive_id`가 append 순서와 강하게 상관돼 BRIN이 페이지 범위를 건너뛴다.
- **접지 않는다. 다만 새 선행 조건이 생겼다** — 주행 하나에 평균 **4,281 샘플**, 최대 **14,386 샘플**, **km당 478개**다. 20km 주행에 좌표 4,281개를 그대로 받을 수는 없으므로 **다운샘플링 방식(간격·개수·알고리즘)을 정하는 것이 4단계 설계의 첫 항목**이다.

- [ ] **Step 5: 앱 저장소에서 커밋한다**

```bash
cd ../woori-haru
git add docs/superpowers/specs/2026-08-17-vehicle-health-dashboard-design.md
git commit -m "docs: 3단계 서버 API를 확정하고 1·2단계 실측을 반영한다"
```

**브랜치를 새로 만들지 마라** — 앱 저장소의 현재 브랜치에 그대로 커밋한다. 커밋 전에 `git status`로 다른 사람의 미완성 변경이 섞여 있지 않은지 확인하고, 있으면 **그 파일만 add 해라.**

---

## 이번 계획에서 하지 않는 것

- **주유비 대비 절감액** — 유가 상수가 필요한데 서버에 두지 않는다. 앱이 정한다.
- **경로·속도 샘플(`positions`)** — 인덱스는 풀렸지만 다운샘플링 설계가 선행돼야 한다. 별건이다.
- **충전 곡선의 다운샘플링** — 서버가 「어느 점을 버릴지」 정하지 않는다.
- **`/tesla/charges/missing-cost`의 창 변경** — 최근 한 달 고정은 의도된 것이고 이 계획이 건드리지 않는다.
