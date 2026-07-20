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

/**
 * 수신 처리 상태.
 * [PENDING]은 원문만 저장되고 아직 처리 결과가 확정되지 않은 상태 — 처리 도중 프로세스가 죽으면
 * 이 상태로 남아, 어떤 원장 변경이든 항상 되짚을 수 있는 원본 기록을 갖는다.
 */
enum class InboundStatus { PENDING, SAVED, CANCEL_MATCHED, PARSE_FAILED }

/** 수신 원문 로그. 파싱 실패해도 원문을 보존해 파서 수정 후 재처리를 가능하게 한다. */
@Entity
@Table(name = "ledger_inbound_messages")
class InboundMessage(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,
    @Column(name = "raw_text", nullable = false, columnDefinition = "text")
    var rawText: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: InboundStatus,
    @Column(name = "entry_id")
    var entryId: Long? = null,
) : BaseEntity()
