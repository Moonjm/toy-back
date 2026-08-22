package com.toy.backend.maintenance

import org.springframework.data.jpa.repository.JpaRepository

interface MaintenanceBillRepository : JpaRepository<MaintenanceBill, Long> {
    fun findByYearMonth(yearMonth: String): MaintenanceBill?

    fun existsByYearMonth(yearMonth: String): Boolean

    /** 추이 조회. `yearMonth`가 `2026-07` 형태라 사전순 비교가 곧 시간순 비교다. */
    fun findByYearMonthGreaterThanEqualOrderByYearMonth(start: String): List<MaintenanceBill>

    fun findAllByOrderByYearMonthDesc(): List<MaintenanceBill>
}
