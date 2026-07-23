package com.toy.backend.ledger.inbound

import java.time.LocalDateTime

/** 파싱 실패로 보존된 수신 원문 — 재처리 화면에서 원문 확인·재시도·삭제에 쓴다. */
data class InboundFailureResponse(
    val id: Long,
    val rawText: String,
    /** 최초 수신 시각 */
    val receivedAt: LocalDateTime,
)
