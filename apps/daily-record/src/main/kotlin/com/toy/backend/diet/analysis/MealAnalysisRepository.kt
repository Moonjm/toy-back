package com.toy.backend.diet.analysis

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDateTime

interface MealAnalysisRepository : JpaRepository<MealAnalysis, Long> {
    fun deleteByCreatedAtBefore(cutoff: LocalDateTime): Long
}
