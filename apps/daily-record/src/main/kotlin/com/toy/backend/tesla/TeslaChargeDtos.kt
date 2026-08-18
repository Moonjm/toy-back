package com.toy.backend.tesla

import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 금액이 빈 충전을 모아 보는 목록. **기간 파라미터는 없고, 범위는 최근 한 달로 고정이다** —
 * 오래된 것까지 다 내리면 채워 넣을 마음이 안 드는 길이가 되고, 실제로 채우는 것은 최근 것이다.
 *
 * `totalCount`는 `limit`과 무관하되 **같은 한 달 창 안의** 개수다. 앱이 배지에 띄우고
 * 채울수록 줄어드는 것을 본다 — 목록만 좁히면 배지와 목록이 어긋나 무엇이 남았는지 읽히지 않는다.
 */
data class MissingCostResponse(
    val totalCount: Int,
    val items: List<ChargeListItem>,
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
    /**
     * 벽에서 뽑아쓴 양. 구버전 데이터에서 null일 수 있다.
     *
     * 상세에만 두지 않는 이유는 **kWh당 단가를 목록에서도 내기 때문이다.** 단가의 분모는
     * 차에 들어간 양이 아니라 요금을 매기는 쪽인 벽에서 뽑아쓴 양이다.
     */
    val energyUsedKwh: BigDecimal?,
    val startBatteryLevel: Int?,
    val endBatteryLevel: Int?,
    val cost: BigDecimal?,
)

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
    /**
     * `/tesla/summary`의 `MonthlyStat.chargeCount`·`cost`와도 다른 수다 — 그쪽은
     * `end_date IS NOT NULL`만 걸어 축퇴 세션을 포함하고(484 기준), 이쪽은 모집단에서 뺀다
     * (474 기준). `missing-cost` 목록·배지에도 축퇴 세션이 올라올 수 있지만 이 `costMissingCount`
     * 에는 없다.
     */
    val chargeCount: Int,
    val energyAddedKwh: BigDecimal?,
    /**
     * 벽에서 뽑아쓴 양. **kWh당 단가의 분모는 이쪽이다** — `ChargeListItem`이 정해 둔 규칙이다.
     *
     * **`fast.energyUsedKwh`(1329.0)가 `fast.energyAddedKwh`(1358.4)보다 작게 나올 수 있다.**
     * 벽에서 뽑아쓴 양이 배터리에 들어간 양보다 작은 것은 물리적으로 불가능한데, 응답이 그렇게
     * 나간다 — 원인은 `used`가 NULL인 세션 2건(`id=14`·`id=29`, 2021년 급속)의 `added`만 합에
     * 들어가서다(2026-08-18 실측). 개별 행이 뒤집힌 것은 42건 중 0건이고, 그 둘을 빼면 급속도
     * 4.8% 손실로 완속(4.9%)과 같다 — TeslaMate가 급속을 과소 기록하는 것이 아니라 NULL 2건 때문에
     * 분자·분모의 모집단이 어긋난 것이다.
     *
     * **그러니 이 값들로 「충전 효율」(added ÷ used)을 내지 마라.** 급속 카드가 102.2%를 표시하게
     * 된다.
     */
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
     * `cost ÷ (energyUsedKwh − costMissingEnergyUsedKwh)`. **분모가 0일 수 있다** —
     * 전부 무료 충전이면 `energyUsedKwh == costMissingEnergyUsedKwh`다. 0으로 나누는 처리는
     * 서버가 정하지 않는다 — 앱이 정한다.
     *
     * 이 값을 빼지 않으면 단가가 낮게 나온다 — 실측(2026-08-18)으로 200.3 vs 211.6원/kWh, 5.6% 차이다.
     *
     * **이 공식의 분모는 대수적으로 `SUM(used) FILTER (cost IS NOT NULL)`과 같은데, `cost`는 있고
     * `used`가 NULL인 세션은 분자에만 들어가고 분모에는 0으로 들어간다.** 즉 이 공식으로도 단가
     * 분모가 완전히 정확하지는 않다 — 실측(2026-08-18)으로 그런 세션이 1건(`id=15`, 10,360원)이고,
     * 단가는 211.64 vs 211.04원/kWh로 0.28% 차이다. 이 저장소는 분모 처리를 앱에 맡겨 왔고
     * 0.28%는 계약을 바꿀 크기가 아니라 코드는 그대로 둔다.
     */
    val costMissingEnergyUsedKwh: BigDecimal?,
    /** 「언제부터의 누적인가」. **모집단(474건) 기준 MIN이다** — 생애 첫 플러그인이 축퇴 세션이었다면
     *  그만큼 밀린다. KST 날짜다. 기록이 없으면 null이다. */
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
