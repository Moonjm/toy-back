package com.toy.backend.ledger

import com.toy.backend.common.constant.Code
import org.springframework.http.HttpStatus

enum class LedgerErrorCode(
    private val httpStatus: HttpStatus,
    private val message: String,
) : Code {
    // ── Inbound ──
    MESSAGE_PARSE_FAILED(HttpStatus.BAD_REQUEST, "메시지를 파싱할 수 없습니다: %s"),
    INBOUND_NOT_RETRYABLE(HttpStatus.BAD_REQUEST, "재처리할 수 없는 수신 메시지입니다: %s"),
    INBOUND_NOT_DELETABLE(HttpStatus.BAD_REQUEST, "실패 상태의 수신 메시지만 삭제할 수 있습니다: %s"),
    ;

    override fun getHttpStatus(): HttpStatus = httpStatus

    override fun getMessage(): String = message

    override fun getStatusName(): String = httpStatus.name

    override fun getCodeName(): String = name
}
