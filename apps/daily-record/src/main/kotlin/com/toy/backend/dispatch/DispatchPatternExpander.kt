package com.toy.backend.dispatch

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 패턴을 날짜별 근무 여부로 펼친다. **상태가 없는 순수 계산**이라 object로 둔다.
 *
 * Kotlin `%`는 음수 피제수에 음수 나머지를 준다(`-7 % 3 == -1`). 기준일 이전 날짜를
 * 조회하는 것이 실제 흐름(기준 8/8, 조회 8/1)이므로 **`Math.floorMod`를 쓴다.**
 */
object DispatchPatternExpander {
    fun isWorking(
        pattern: MotherPatternProperties,
        date: LocalDate,
    ): Boolean {
        val elapsed = ChronoUnit.DAYS.between(pattern.anchorDate, date)
        val offset = Math.floorMod(elapsed, pattern.cycleDays.toLong()).toInt()
        return offset in pattern.workingOffsetList
    }
}
