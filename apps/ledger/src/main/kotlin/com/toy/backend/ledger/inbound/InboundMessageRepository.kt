package com.toy.backend.ledger.inbound

import org.springframework.data.jpa.repository.JpaRepository

interface InboundMessageRepository : JpaRepository<InboundMessage, Long>
