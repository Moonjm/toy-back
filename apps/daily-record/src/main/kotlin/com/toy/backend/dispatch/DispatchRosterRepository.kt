package com.toy.backend.dispatch

import org.springframework.data.jpa.repository.JpaRepository

interface DispatchRosterRepository : JpaRepository<DispatchRoster, Long> {
    fun findByYearMonth(yearMonth: String): DispatchRoster?

    /**
     * 가장 최근 달의 기준. **연월을 모르는 사진에만 쓴다.**
     *
     * `yearMonth`는 `2026-08` 형식 문자열이라 사전순 내림차순이 곧 시간순 내림차순이다.
     * 파생 쿼리 이름이 엔티티 필드명 `yearMonth`와 맞아야 기동한다 — 이 저장소에는
     * 컨텍스트를 띄우는 테스트가 없어 오타는 실행 시점에야 드러난다.
     */
    fun findTopByOrderByYearMonthDesc(): DispatchRoster?
}
