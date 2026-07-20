package com.toy.backend.ledger.inbound

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface InboundMessageRepository : JpaRepository<InboundMessage, Long> {
    /**
     * 재처리 대상을 원자적으로 선점한다. PARSE_FAILED인 동안에만 PENDING으로 바뀌므로,
     * 동시 요청 중 하나만 1을 돌려받아 실제 처리를 수행한다(나머지는 0).
     */
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
