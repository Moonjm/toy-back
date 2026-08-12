package com.toy.backend.diet.analysis

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class MealAnalysisCleanupService(
    private val repository: MealAnalysisRepository,
) {
    @Transactional
    fun purgeExpired(cutoff: LocalDateTime): Long = repository.deleteByCreatedAtBefore(cutoff)
}
