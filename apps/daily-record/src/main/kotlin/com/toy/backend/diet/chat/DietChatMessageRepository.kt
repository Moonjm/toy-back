package com.toy.backend.diet.chat

import com.toy.backend.user.User
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.time.LocalDateTime

interface DietChatMessageRepository : JpaRepository<DietChatMessage, Long> {
    /**
     * 히스토리 전량. **턴 수도 이 목록에서 센다** — 어차피 전부 읽어야 하므로 `count` 쿼리를
     * 따로 두지 않는다.
     */
    fun findByUserAndDateOrderByIdAsc(
        user: User,
        date: LocalDate,
    ): List<DietChatMessage>

    /** 정리 배치용. `MealAnalysisRepository`와 같은 모양이다. */
    fun deleteByCreatedAtBefore(cutoff: LocalDateTime): Long
}
