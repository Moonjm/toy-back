package com.toy.backend.study

import com.toy.backend.common.entity.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "study_subjects")
class StudySubject(
    @Column(nullable = false, length = 50)
    var name: String,
    @Column(nullable = false)
    var sortOrder: Int = 0,
) : BaseEntity() {
    fun updateDetails(name: String) {
        this.name = name
    }

    fun updateSortOrder(sortOrder: Int) {
        this.sortOrder = sortOrder
    }
}

data class StudySubjectResponse(
    val id: Long,
    val name: String,
)

fun StudySubject.toResponse(): StudySubjectResponse = StudySubjectResponse(id = requiredId, name = name)
