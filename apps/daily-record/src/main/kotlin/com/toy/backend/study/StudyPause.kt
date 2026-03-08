package com.toy.backend.study

import com.toy.backend.common.entity.BaseEntity
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "study_pauses")
class StudyPause(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    var session: StudySession,

    @Column(nullable = false)
    var pausedAt: LocalDateTime,

    @Column(nullable = true)
    var resumedAt: LocalDateTime? = null,
) : BaseEntity()
