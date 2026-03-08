package com.toy.backend.study

import com.toy.backend.user.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.LocalDateTime

interface StudySessionRepository : JpaRepository<StudySession, Long> {
    fun findByIdAndUser(id: Long, user: User): StudySession?
    fun existsByUserAndEndedAtIsNull(user: User): Boolean
    fun existsBySubject(subject: StudySubject): Boolean

    @Query(
        """
        SELECT s FROM StudySession s
        JOIN FETCH s.subject
        LEFT JOIN FETCH s.pauses
        WHERE s.user = :user AND s.endedAt IS NULL
        """
    )
    fun findByUserAndEndedAtIsNull(user: User): StudySession?

    @Query(
        """
        SELECT s FROM StudySession s
        JOIN FETCH s.subject
        LEFT JOIN FETCH s.pauses
        WHERE s.user = :user
          AND s.startedAt BETWEEN :from AND :to
        ORDER BY s.startedAt DESC
        """
    )
    fun findAllByUserAndStartedAtBetweenOrderByStartedAtDesc(
        user: User,
        from: LocalDateTime,
        to: LocalDateTime,
    ): List<StudySession>
}
