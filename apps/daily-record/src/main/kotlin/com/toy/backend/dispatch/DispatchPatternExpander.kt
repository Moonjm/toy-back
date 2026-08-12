package com.toy.backend.dispatch

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 엄마 근무 주기를 날짜별 근무 여부로 펼친다. **상태가 없는 순수 계산**이라 object로 둔다.
 *
 * 주기는 「하루 휴무 → 이틀 근무」가 3일마다 도는 형태이고 2026-08-08이 휴무다.
 *
 * **설정으로 빼지 않고 여기 상수로 둔다.** 사람이 바꿀 값이 아닌데 설정으로 빼면 값 셋을
 * 위해 프로퍼티 클래스·기동 검증·설정 블록·환경변수가 딸려 오고, 무엇보다 **잘못된 값이
 * 들어올 경로가 생긴다** — 주기 밖 오프셋을 넣으면 그 근무일이 조용히 휴무가 되어, 그것을
 * 막는 검증을 또 붙여야 했다. 상수면 그 경로 자체가 없다. 근무 형태가 바뀌면 여기를 고치고
 * 배포한다(`./deploy.sh daily-record`).
 *
 * Kotlin `%`는 음수 피제수에 음수 나머지를 준다(`-7 % 3 == -1`). 기준일 이전 날짜를
 * 조회하는 것이 실제 흐름(기준 8/8, 조회 8/1)이므로 **`Math.floorMod`를 쓴다.**
 */
object DispatchPatternExpander {
    /** 오프셋 0인 날. 이 날이 휴무다. */
    private val ANCHOR_DATE: LocalDate = LocalDate.of(2026, 8, 8)

    private const val CYCLE_DAYS = 3L

    /** 주기 안에서 일하는 날의 오프셋. 여기 없는 오프셋(0)이 휴무다. */
    private val WORKING_OFFSETS = setOf(1, 2)

    fun isWorking(date: LocalDate): Boolean {
        val elapsed = ChronoUnit.DAYS.between(ANCHOR_DATE, date)
        return Math.floorMod(elapsed, CYCLE_DAYS).toInt() in WORKING_OFFSETS
    }
}
