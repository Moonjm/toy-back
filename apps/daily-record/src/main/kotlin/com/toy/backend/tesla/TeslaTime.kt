package com.toy.backend.tesla

import java.time.Duration
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

    /**
     * 범위 안에서 그 달이 실제로 몇 분 지났는가 — 정지 시간의 분모다.
     *
     * **달 전체 분으로 잡으면 안 된다.** 8월 20일에 44,640분을 쓰면 아직 오지 않은 11일치가
     * 정지 시간이 되어 진행 중인 달만 막대가 솟는다. 겹치지 않는 달은 0이다(분모라 음수 금지).
     */
    fun monthElapsedMinutes(
        month: YearMonth,
        startKst: LocalDateTime,
        endKst: LocalDateTime,
    ): Int {
        val from = maxOf(month.atDay(1).atStartOfDay(), startKst)
        val to = minOf(month.plusMonths(1).atDay(1).atStartOfDay(), endKst)
        if (!from.isBefore(to)) return 0
        return Duration.between(from, to).toMinutes().toInt()
    }

    /**
     * 요일별 등장 일수와 실제로 흐른 분. **키는 1이 월요일**(ISO), 일곱 개가 늘 있다.
     *
     * 둘을 갈라 내는 이유는 오늘이 반쪽이라서다 — 요일 평균의 분모(`occurrences`)로는 오늘도
     * 한 번이지만(그날 주행이 이미 분자에 있다), 정지 시간의 분모(`elapsedMin`)는 지금까지뿐이다.
     */
    fun weekdaySpans(
        startKst: LocalDateTime,
        endKst: LocalDateTime,
    ): Map<Int, WeekdaySpan> {
        val occurrences = IntArray(8)
        val minutes = IntArray(8)
        var dayStart = startKst.toLocalDate().atStartOfDay()
        while (dayStart.isBefore(endKst)) {
            val dayEnd = dayStart.plusDays(1)
            val from = maxOf(dayStart, startKst)
            val to = minOf(dayEnd, endKst)
            if (from.isBefore(to)) {
                val weekday = dayStart.dayOfWeek.value
                occurrences[weekday]++
                minutes[weekday] += Duration.between(from, to).toMinutes().toInt()
            }
            dayStart = dayEnd
        }
        return (1..7).associateWith { WeekdaySpan(occurrences[it], minutes[it]) }
    }

    /** 근거는 `weekdaySpans`. */
    data class WeekdaySpan(
        val occurrences: Int,
        val elapsedMin: Int,
    )
}
