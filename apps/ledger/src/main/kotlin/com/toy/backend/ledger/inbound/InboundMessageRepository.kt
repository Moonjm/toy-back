package com.toy.backend.ledger.inbound

import com.toy.backend.user.User
import org.springframework.data.jpa.repository.JpaRepository

interface InboundMessageRepository : JpaRepository<InboundMessage, Long> {
    /**
     * 소유자까지 조건으로 걸어 조회한다 — 타인 소유 건은 애초에 나오지 않고(존재 숨김),
     * 트랜잭션 밖에서 `message.user`(LAZY 프록시)에 접근할 필요도 없다.
     */
    fun findByIdAndUser(
        id: Long,
        user: User,
    ): InboundMessage?
}
