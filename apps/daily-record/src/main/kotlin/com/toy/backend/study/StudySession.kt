package com.toy.backend.study

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.entity.BaseEntity
import com.toy.backend.common.exception.CustomException
import com.toy.backend.user.User
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subject_id", nullable = false)
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
        if (endedAt != null) throw CustomException(ErrorCode.INVALID_REQUEST, "이미 종료된 세션입니다")
        if (pauses.any { it.resumedAt == null }) throw CustomException(ErrorCode.INVALID_REQUEST, "이미 일시정지 중입니다")
        val pause = StudyPause(session = this, pausedAt = at)
        pauses.add(pause)
        return pause
    }

    fun activePause(): StudyPause? = pauses.singleOrNull { it.resumedAt == null }

    fun resume(at: LocalDateTime) {
        if (endedAt != null) throw CustomException(ErrorCode.INVALID_REQUEST, "이미 종료된 세션입니다")
        val pause = activePause()
            ?: throw CustomException(ErrorCode.INVALID_REQUEST, "활성 일시정지가 없습니다")
        pause.resumedAt = at
    }

    fun updatePauseType(type: PauseType) {
        if (endedAt != null) throw CustomException(ErrorCode.INVALID_REQUEST, "이미 종료된 세션입니다")
        val pause = activePause()
            ?: throw CustomException(ErrorCode.INVALID_REQUEST, "활성 일시정지가 없습니다")
        pause.updateType(type)
    }

    fun end(at: LocalDateTime) {
        if (endedAt != null) throw CustomException(ErrorCode.INVALID_REQUEST, "이미 종료된 세션입니다")
        activePause()?.resumedAt = at
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
