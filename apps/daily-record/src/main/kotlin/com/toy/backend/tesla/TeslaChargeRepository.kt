package com.toy.backend.tesla

import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * TeslaMate DB 접근. **행 타입의 시각은 전부 UTC다** — TeslaMate가 타임존 없는 timestamp에
 * UTC 값을 넣기 때문이다. KST 변환은 서비스가 한다.
 */
interface TeslaChargeRepository {
    /** `cost IS NULL AND end_date IS NOT NULL`을 `start_date DESC`로. **최근 한 달**만 낸다. */
    fun findMissingCost(limit: Int): List<ChargeRow>

    /** `limit`과 무관한 전체 개수. */
    fun countMissingCost(): Int

    /** 없으면 null. 진행 중(`end_date IS NULL`)인 행도 없는 것으로 본다. */
    fun findDetail(id: Long): ChargeDetailRow?

    /** 샘플이 하나도 없어도 행은 온다 — 모든 필드가 null인 행이다. */
    fun findChargeStats(id: Long): ChargeStatsRow

    /**
     * TeslaMate DB에 쓰는 **유일한** 자리다. 영향 행 수를 돌려준다 —
     * 없는 id와 진행 중인 행이 모두 0이 된다.
     */
    fun updateCost(
        id: Long,
        cost: BigDecimal,
    ): Int

    /**
     * 월별 충전 집계. **월 경계는 KST 기준**이다 — SQL이 UTC 값을 KST로 옮긴 뒤 자른다.
     * 데이터가 없는 달은 행이 오지 않는다(0행이지 0값이 아니다).
     */
    fun chargeMonthly(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<ChargeMonthRow>

    /** 그 달의 충전 목록. 진행 중은 제외하고 `start_date DESC`. */
    fun findMonthCharges(
        startUtc: LocalDateTime,
        endUtcExclusive: LocalDateTime,
    ): List<ChargeRow>

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
}
