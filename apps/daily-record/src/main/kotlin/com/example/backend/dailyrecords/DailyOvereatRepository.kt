package com.example.backend.dailyrecords

import com.example.backend.user.User
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface DailyOvereatRepository : JpaRepository<DailyOvereat, Long> {
    fun findByDateAndUser(
        date: LocalDate,
        user: User,
    ): DailyOvereat?

    fun findAllByUserAndDateBetween(
        user: User,
        dateStart: LocalDate,
        dateEnd: LocalDate,
    ): List<DailyOvereat>
}
