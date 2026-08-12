package com.toy.backend.dispatch

import org.springframework.data.jpa.repository.JpaRepository

interface DispatchRosterRepository : JpaRepository<DispatchRoster, Long> {
    fun findByYearMonth(yearMonth: String): DispatchRoster?
}
