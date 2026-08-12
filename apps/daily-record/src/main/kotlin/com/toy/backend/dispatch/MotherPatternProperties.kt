package com.toy.backend.dispatch

import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.LocalDate

/**
 * 엄마 근무 주기. **저장소가 아니라 설정에 둔다** — 등록하지 않으면 달력에서 엄마가 통째로
 * 비어 보이는데, 그게 「쉬는 날」인지 「등록을 안 한 것」인지 화면만으로는 구분되지 않는다.
 * 주기는 사람이 자주 바꾸는 값도 아니다.
 *
 * `anchorDate`는 **오프셋 0인 날**이고, `workingOffsets`에 없는 오프셋이 휴무다.
 * 기본값은 `하루 휴무 → 이틀 근무`가 3일 주기로 도는 형태다(2026-08-08이 휴무).
 *
 * **잘못된 설정을 조용히 삼키지 않는다.** 문자열을 직접 잘라 `mapNotNull`로 걸렀을 때는
 * `1,x` 같은 오타가 소리 없이 버려져 엄마가 매일 휴무로 나왔다. 타입을 `List<Int>`로 두면
 * 바인딩 자체가 기동 시점에 실패한다. `cycleDays = 0`도 마찬가지다 — 그대로 두면
 * `Math.floorMod`가 던지는 예외가 무인증 엔드포인트에서 500이 된다.
 */
@Validated
@ConfigurationProperties(prefix = "dispatch.mother-pattern")
data class MotherPatternProperties(
    @field:Min(1) val cycleDays: Int = 3,
    val workingOffsets: List<Int> = listOf(1, 2),
    val anchorDate: LocalDate = LocalDate.of(2026, 8, 8),
) {
    /**
     * 오프셋은 `Math.floorMod(elapsed, cycleDays)`와 비교되므로 **`0 until cycleDays` 안에
     * 있어야 한다.** 범위 밖 값(`cycleDays = 3`에 `5`, 또는 음수)은 영원히 일치하지 않아
     * 그 근무일이 조용히 휴무로 처리된다 — 오타 하나가 소리 없이 사라지던 것과 같은 실패다.
     *
     * **빈 목록도 막는다.** 매일 휴무가 되는데, 그건 「주기 설정」이 아니라 설정을 빠뜨린
     * 것에 가깝다. 화면에서는 둘을 구분할 수 없다.
     *
     * `@get:AssertTrue`라 `@field:Min(1)`과 같이 **기동 시점에** 걸린다.
     */
    @get:AssertTrue(message = "workingOffsets는 비어 있지 않아야 하고 모든 값이 0 이상 cycleDays 미만이어야 합니다")
    val offsetsWithinCycle: Boolean
        get() = workingOffsets.isNotEmpty() && workingOffsets.all { it in 0 until cycleDays }
}
