package com.toy.backend.ledger.inbound

import com.toy.backend.common.entity.BaseEntity
import com.toy.backend.user.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

enum class InboundStatus { SAVED, CANCEL_MATCHED, PARSE_FAILED }

/** 수신 원문 로그. 파싱 실패해도 원문을 보존해 파서 수정 후 재처리를 가능하게 한다. */
@Entity
@Table(name = "ledger_inbound_messages")
class InboundMessage(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,
    @Column(name = "raw_text", nullable = false, columnDefinition = "text")
    var rawText: String,
    // columnDefinition을 명시해 Hibernate의 enum CHECK 제약 생성을 막는다 —
    // ddl-auto:update는 기존 제약을 갱신하지 않아 enum 값을 추가하면 기존 DB에서 INSERT가 실패한다.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20)")
    var status: InboundStatus,
    @Column(name = "entry_id")
    var entryId: Long? = null,
) : BaseEntity()
