package com.toy.backend.dispatch

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.LocalDate

/**
 * 엄마 근무 주기. **저장소가 아니라 설정에 둔다** — 등록하지 않으면 달력에서 엄마가 통째로
 * 비어 보이는데, 그게 「쉬는 날」인지 「등록을 안 한 것」인지 화면만으로는 구분되지 않는다.
 * 주기는 사람이 자주 바꾸는 값도 아니다.
 *
 * `anchorDate`는 **오프셋 0인 날**이고, `workingOffsets`에 없는 오프셋이 휴무다.
 * 기본값은 `하루 휴무 → 이틀 근무`가 3일 주기로 도는 형태다(2026-08-08이 휴무).
 */
@ConfigurationProperties(prefix = "dispatch.mother-pattern")
data class MotherPatternProperties(
    val cycleDays: Int = 3,
    val workingOffsets: String = "1,2",
    val anchorDate: LocalDate = LocalDate.of(2026, 8, 8),
) {
    val workingOffsetList: List<Int>
        get() = workingOffsets.split(",").mapNotNull { it.trim().toIntOrNull() }
}
