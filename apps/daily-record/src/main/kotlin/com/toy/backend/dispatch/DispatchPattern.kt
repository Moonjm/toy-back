package com.toy.backend.dispatch

import com.toy.backend.common.entity.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.LocalDate

/**
 * 반복 근무 규칙. **행을 미리 만들어 두지 않고 조회할 때 계산한다** — 미리 만들면
 * 패턴을 고칠 때 낡은 행이 남고, 어디까지 만들어 뒀는지도 관리해야 한다.
 *
 * `anchorDate`는 **오프셋 0인 날**이다. `workingOffsets`에 없는 오프셋이 휴무다.
 * 엄마는 `cycleDays=3`, `workingOffsets="1,2"`, `anchorDate=2026-08-08`(휴무)이다.
 */
@Entity
@Table(name = "dispatch_pattern")
class DispatchPattern(
    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "role", nullable = false, unique = true, columnDefinition = "varchar(16)")
    val role: DispatchRole,
    @field:Column(name = "cycle_days", nullable = false)
    var cycleDays: Int,
    @field:Column(name = "working_offsets", nullable = false, length = 64)
    var workingOffsets: String,
    @field:Column(name = "anchor_date", nullable = false)
    var anchorDate: LocalDate,
) : BaseEntity() {
    val workingOffsetList: List<Int>
        get() = workingOffsets.split(",").mapNotNull { it.trim().toIntOrNull() }
}
