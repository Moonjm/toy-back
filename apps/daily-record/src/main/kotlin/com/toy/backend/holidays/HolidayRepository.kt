package com.toy.backend.holidays

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface HolidayRepository : JpaRepository<Holiday, Long> {
    fun findByDateBetweenOrderByDate(
        start: LocalDate,
        end: LocalDate,
    ): List<Holiday>

    fun deleteByDateBetween(
        start: LocalDate,
        end: LocalDate,
    )
}
