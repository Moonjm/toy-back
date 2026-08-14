package com.toy.backend.tesla

import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * TeslaMate DB 접근. **행 타입의 시각은 전부 UTC다** — TeslaMate가 타임존 없는 timestamp에
 * UTC 값을 넣기 때문이다. KST 변환은 서비스가 한다.
 */
interface TeslaChargeRepository {
    /** `cost IS NULL AND end_date IS NOT NULL`을 `start_date DESC`로. 기간 필터가 없다. */
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
}
