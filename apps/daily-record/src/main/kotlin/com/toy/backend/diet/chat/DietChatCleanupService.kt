package com.toy.backend.diet.chat

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class DietChatCleanupService(
    private val repository: DietChatMessageRepository,
) {
    @Transactional
    fun purgeExpired(cutoff: LocalDateTime): Long = repository.deleteByCreatedAtBefore(cutoff)
}
