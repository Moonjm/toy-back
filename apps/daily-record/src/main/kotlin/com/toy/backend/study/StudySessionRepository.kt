package com.toy.backend.study

import com.toy.backend.user.User
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDateTime

interface StudySessionRepository : JpaRepository<StudySession, Long> {
    fun findByIdAndUser(id: Long, user: User): StudySession?
    fun findAllByUserAndStartedAtBetweenOrderByStartedAtDesc(
        user: User,
        from: LocalDateTime,
        to: LocalDateTime,
    ): List<StudySession>
}
