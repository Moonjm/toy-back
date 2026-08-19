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

    /** 지금(KST). 범위 계산을 순수 함수로 두려고 시각 읽기만 여기로 뺀다. */
    fun nowKst(): LocalDateTime = LocalDateTime.now(KST)

    /**
     * 최근 `hours`시간의 타임라인 범위(KST). `first`가 시작, `second`가 끝이다.
     *
     * **지금부터 거꾸로 센다 — 자정에 맞추지 않는다.** 초판은 앱이 하루 한 행씩 그렸기
     * 때문에 시작을 KST 자정에 맞췄다(임의 시각에서 시작하면 첫 행과 마지막 행이 반쪽이
     * 됐다). 지금 앱은 24시간을 **한 줄로** 그리고 오른쪽 끝이 「지금」이다 — 자정에 맞추면
     * 그 끝이 「지금」이 아니게 되므로, 정렬이 문제를 풀던 자리에서 문제를 만드는 자리로
     * 바뀌었다.
     *
     * 끝은 요청 시각 그대로다 — 진행 중인 상태·주행·충전을 여기서 막는다.
     */
    fun timelineWindowKst(
        hours: Int,
        nowKst: LocalDateTime = nowKst(),
    ): Pair<LocalDateTime, LocalDateTime> = nowKst.minusHours(hours.toLong()) to nowKst
}
