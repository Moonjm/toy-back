package com.toy.backend.tesla

import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.time.LocalDateTime

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
