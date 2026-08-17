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
    /**
     * 사용 가능 용량(kWh). 표본 조건은 `end_battery_level >= 80`이면서 ΔSoC ≥ 40인 충전이다
     * (용량 표본도 만충 환산 표본과 같은 공통 WHERE 위에 얹혀 있다). 그런 충전이 그 달에
     * 없으면 **null이다. 0이 아니다.**
     */
    val capacityKwh: BigDecimal?,
    val sampleCount: Int,
    val capacitySampleCount: Int,
)
