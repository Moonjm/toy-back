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
