package com.toy.backend.maintenance

import org.springframework.data.jpa.repository.JpaRepository

interface MaintenanceBillRepository : JpaRepository<MaintenanceBill, Long> {
    fun findByYearMonth(yearMonth: String): MaintenanceBill?

    fun existsByYearMonth(yearMonth: String): Boolean

    /**
     * 추이 조회. **상한도 건다** — 하한만 걸면 잘못 들어간 미래 달이 모든 응답에 딸려 와
     * 「최근 N개월」이 계약이 아니게 된다.
     */
    fun findByYearMonthBetweenOrderByYearMonth(
        start: String,
        end: String,
    ): List<MaintenanceBill>

    fun findAllByOrderByYearMonthDesc(): List<MaintenanceBill>
}
