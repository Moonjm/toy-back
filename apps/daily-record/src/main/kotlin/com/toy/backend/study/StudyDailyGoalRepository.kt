package com.toy.backend.study

import com.toy.backend.user.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.LocalDate

interface StudyDailyGoalRepository : JpaRepository<StudyDailyGoal, Long> {
    fun findByUserAndDate(user: User, date: LocalDate): StudyDailyGoal?

    @Query("SELECT COALESCE(SUM(g.goalMinutes), 0) FROM StudyDailyGoal g WHERE g.user = :user AND g.date BETWEEN :from AND :to")
    fun sumGoalMinutesByUserAndDateBetween(user: User, from: LocalDate, to: LocalDate): Int
}
