package com.toy.backend.dispatch

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface DispatchShiftRepository : JpaRepository<DispatchShift, Long> {
    fun findByWorkDateBetween(
        from: LocalDate,
        to: LocalDate,
    ): List<DispatchShift>

    fun findByRoleAndWorkDate(
        role: DispatchRole,
        workDate: LocalDate,
    ): DispatchShift?
}
