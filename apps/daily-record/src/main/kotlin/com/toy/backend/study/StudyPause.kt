package com.toy.backend.study

import com.toy.backend.common.entity.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
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
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var type: PauseType = PauseType.REST,
) : BaseEntity() {
    fun updateType(type: PauseType) {
        this.type = type
    }
}
