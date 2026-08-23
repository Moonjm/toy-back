package com.toy.backend.maintenance

import org.springframework.data.jpa.repository.JpaRepository

interface MaintenanceBillRepository : JpaRepository<MaintenanceBill, Long> {
    fun findByYearMonth(yearMonth: String): MaintenanceBill?

    fun existsByYearMonth(yearMonth: String): Boolean

    /**
     * 추이 조회. `yearMonth`가 `2026-07` 형태라 사전순 비교가 곧 시간순 비교다.
     *
     * **하한만 걸지 않고 상한도 건다.** 하한만 걸면 「최근 N개월」이 계약이 아니게 된다 —
     * 검수 화면에서 연월을 잘못 골라 미래 달로 들어간 레코드가 이후 모든 응답에 딸려 와,
     * N을 아무리 줘도 그보다 많은 달이 나오고 그래프 오른쪽에 혼자 떨어진 점이 붙는다.
     * 영수증의 연월은 늘 과거이므로 미래 달은 언제나 잘못 들어온 것이다.
     */
    fun findByYearMonthBetweenOrderByYearMonth(
        start: String,
        end: String,
    ): List<MaintenanceBill>

    fun findAllByOrderByYearMonthDesc(): List<MaintenanceBill>
}
