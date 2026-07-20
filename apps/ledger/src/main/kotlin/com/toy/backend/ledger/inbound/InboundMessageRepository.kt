package com.toy.backend.ledger.inbound

import com.toy.backend.user.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

interface InboundMessageRepository : JpaRepository<InboundMessage, Long> {
    /**
     * 소유자까지 조건으로 걸어 조회한다 — 타인 소유 건은 애초에 나오지 않고(존재 숨김),
     * 트랜잭션 밖에서 `message.user`(LAZY 프록시)에 접근할 필요도 없다.
     */
    fun findByIdAndUser(
        id: Long,
        user: User,
    ): InboundMessage?

    /**
     * 재처리 대상을 원자적으로 선점한다. PARSE_FAILED인 동안에만 PENDING으로 바뀌므로,
     * 동시 요청 중 하나만 1을 돌려받아 실제 처리를 수행한다(나머지는 0).
     *
     * 선언형 쿼리 메서드는 CRUD 기본 트랜잭션을 물려받지 않으므로 자체 경계가 필요하다.
     * 선점만 즉시 커밋되어야 다른 요청이 곧바로 0을 받는다(REQUIRES_NEW).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update InboundMessage m
        set m.status = com.toy.backend.ledger.inbound.InboundStatus.PENDING
        where m.id = :id and m.status = com.toy.backend.ledger.inbound.InboundStatus.PARSE_FAILED
        """,
    )
    fun claimForRetry(
        @Param("id") id: Long,
    ): Int
}
