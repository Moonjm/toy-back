package com.toy.backend.diet.chat

import com.toy.backend.user.User
import org.springframework.data.domain.Pageable
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

    /**
     * 프롬프트에 실을 히스토리. **`createdAt` 기준**이고 `id DESC`라 최근 것부터 온다 —
     * 개수는 `Pageable`로 자른다. 시간순으로 뒤집는 것은 부르는 쪽의 몫이다.
     *
     * **`date`가 아니라 `createdAt`이다.** 「어느 날 밥 얘기인가」(`date`)와 「어느 대화를
     * 기억하는가」(`createdAt`)는 축이 다르다(설계 「세 창이 서로 다르다」).
     */
    fun findByUserAndCreatedAtAfterOrderByIdDesc(
        user: User,
        createdAt: LocalDateTime,
        pageable: Pageable,
    ): List<DietChatMessage>
}
