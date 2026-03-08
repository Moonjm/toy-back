package com.toy.backend.study

import com.toy.backend.common.entity.BaseEntity
import com.toy.backend.user.User
import jakarta.persistence.*
import java.time.Duration
import java.time.LocalDateTime

@Entity
@Table(
    name = "study_sessions",
    indexes = [
        Index(name = "idx_study_sessions_user", columnList = "user_id"),
        Index(name = "idx_study_sessions_started", columnList = "started_at"),
    ],
)
class StudySession(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var subject: StudySubject,

    @Column(nullable = false)
    var startedAt: LocalDateTime,

    @Column(nullable = true)
    var endedAt: LocalDateTime? = null,

    @Column(nullable = false)
    var totalSeconds: Long = 0,

    @OneToMany(mappedBy = "session", cascade = [CascadeType.ALL], orphanRemoval = true)
    var pauses: MutableList<StudyPause> = mutableListOf(),
) : BaseEntity() {

    fun pause(at: LocalDateTime): StudyPause {
        val pause = StudyPause(session = this, pausedAt = at)
        pauses.add(pause)
        return pause
    }

    fun resume(at: LocalDateTime) {
        val activePause = pauses.lastOrNull { it.resumedAt == null }
            ?: error("No active pause to resume")
        activePause.resumedAt = at
    }

    fun end(at: LocalDateTime) {
        endedAt = at
        totalSeconds = calculateTotalSeconds(at)
    }

    private fun calculateTotalSeconds(endTime: LocalDateTime): Long {
        val totalDuration = Duration.between(startedAt, endTime)
        val pausedDuration = pauses.sumOf { pause ->
            val resumeTime = pause.resumedAt ?: endTime
            Duration.between(pause.pausedAt, resumeTime).seconds
        }
        return totalDuration.seconds - pausedDuration
    }
}
