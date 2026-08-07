package com.toy.backend.diet.feedback

import com.toy.backend.user.User
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface DailyDietFeedbackRepository : JpaRepository<DailyDietFeedback, Long> {
    fun findByUserAndDate(
        user: User,
        date: LocalDate,
    ): DailyDietFeedback?

    /**
     * 끼니 삭제·활동 에너지 변경 시 캐시를 지운다. 남은 끼니의 `updatedAt`은 그대로라
     * `resolveFeedback`의 무효화 조건(`generatedAt` < 최종 `updatedAt`)만으로는 잡히지 않는다.
     */
    fun deleteByUserAndDate(
        user: User,
        date: LocalDate,
    ): Long

    /** 채팅 한 장에 실린 총평 카드들을 한 번에 읽는다. */
    fun findByUserAndDateIn(
        user: User,
        dates: Collection<LocalDate>,
    ): List<DailyDietFeedback>
}
