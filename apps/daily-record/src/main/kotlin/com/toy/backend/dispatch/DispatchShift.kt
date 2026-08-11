package com.toy.backend.dispatch

import com.toy.backend.common.entity.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDate

/**
 * 확정된 근무 한 행. **아빠는 모든 날이 여기 들어오고, 엄마는 패턴과 다른 날(예외)만 들어온다.**
 *
 * `working`이 판정의 유일한 근거다. `slot`은 배차 순번이고 **근무여도 미정이면 null**이다
 * (엄마는 순번 구분이 있지만 아직 넣지 않는다). 둘을 겹쳐 쓰면 순번을 채우는 날
 * 판정 로직을 읽는 쪽마다 전부 고쳐야 한다.
 */
@Entity
@Table(
    name = "dispatch_shift",
    uniqueConstraints = [UniqueConstraint(columnNames = ["role", "work_date"])],
)
class DispatchShift(
    // ddl-auto가 CHECK 제약을 갱신하지 못해, 명시하지 않으면 enum 값 추가 시 기존 DB에서 INSERT가 깨진다.
    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "role", nullable = false, columnDefinition = "varchar(16)")
    val role: DispatchRole,
    @field:Column(name = "work_date", nullable = false)
    val workDate: LocalDate,
    @field:Column(name = "working", nullable = false)
    var working: Boolean,
    @field:Column(name = "slot")
    var slot: Int? = null,
    @field:Column(name = "note")
    var note: String? = null,
) : BaseEntity()
