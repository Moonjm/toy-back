package com.toy.backend.study

import com.toy.backend.user.User
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface StudyDailyGoalRepository : JpaRepository<StudyDailyGoal, Long> {
    fun findByUserAndDate(user: User, date: LocalDate): StudyDailyGoal?
}
