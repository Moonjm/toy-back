package com.toy.backend.diet.chat

import com.toy.backend.user.User
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDateTime

interface DietChatMessageRepository : JpaRepository<DietChatMessage, Long> {
    /**
     * 프롬프트에 실을 히스토리. **`createdAt` 기준**이고 `id DESC`라 최근 것부터 온다 —
     * 개수는 `Pageable`로 자른다. 시간순으로 뒤집는 것은 부르는 쪽의 몫이다.
     *
     * **`date`가 아니라 `createdAt`이다.** 「어느 날 밥 얘기인가」(`date`)와 「어느 대화를
     * 기억하는가」(`createdAt`)는 축이 다르다(설계 「세 창이 서로 다르다」).
     *
     * **[type]으로 카드를 걸러 낸다.** 카드가 20턴 창에 섞이면 정작 대화가 밀려나고,
     * 내용도 이미 `[끼니별 상세]`로 매 요청 실려 중복이다.
     */
    fun findByUserAndTypeAndCreatedAtAfterOrderByIdDesc(
        user: User,
        type: ChatMessageType,
        createdAt: LocalDateTime,
        pageable: Pageable,
    ): List<DietChatMessage>

    /**
     * 화면용 페이징. **날짜로 자르지 않는다** — 사용자 전체가 한 스트림이다.
     *
     * `id`가 단조 증가라 `offset` 없이 안정적으로 뒤로 간다. 중간에 새 메시지가 들어와도
     * 이미 읽은 페이지가 밀리지 않는다.
     */
    fun findByUserAndIdLessThanOrderByIdDesc(
        user: User,
        id: Long,
        pageable: Pageable,
    ): List<DietChatMessage>
}
