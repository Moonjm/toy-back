package com.toy.backend.study

import org.springframework.data.jpa.repository.JpaRepository

interface StudySubjectRepository : JpaRepository<StudySubject, Long> {
    fun findAllByOrderBySortOrderAscIdAsc(): List<StudySubject>
}
