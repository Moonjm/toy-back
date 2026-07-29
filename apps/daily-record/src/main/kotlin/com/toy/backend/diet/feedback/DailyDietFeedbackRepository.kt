package com.toy.backend.diet.feedback

import com.toy.backend.user.User
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface DailyDietFeedbackRepository : JpaRepository<DailyDietFeedback, Long> {
    fun findByUserAndDate(
        user: User,
        date: LocalDate,
    ): DailyDietFeedback?
}
