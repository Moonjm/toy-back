package com.toy.backend.diet.daily

import com.toy.backend.user.User
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface DailyActivityRepository : JpaRepository<DailyActivity, Long> {
    fun findByUserAndDate(
        user: User,
        date: LocalDate,
    ): DailyActivity?
}
