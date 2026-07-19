package com.toy.backend.ledger.inbound

import com.toy.backend.ledger.entries.EntrySource
import java.math.BigDecimal
import java.time.LocalDateTime

enum class ParsedKind { APPROVAL, CANCEL }

data class ParsedMessage(
    val kind: ParsedKind,
    val amount: BigDecimal,
    val currency: String,
    val merchant: String?,
    val occurredAt: LocalDateTime,
    val source: EntrySource,
    val description: String? = null,
)

/**
 * 수신 문자/알림 텍스트 파서.
 *
 * 계약: [parse]는 [supports]가 true를 반환한 텍스트에 대해서만 호출해야 한다.
 * 구현체는 이 전제 하에 형식이 보장된 것으로 간주하고 파싱한다 (파서 체인이 supports로 선별).
 * 이 계약을 위반해 [parse]가 예외를 던지면 InboundService가 PARSE_FAILED로 흡수해 원문을 보존한다.
 */
interface MessageParser {
    fun supports(text: String): Boolean

    fun parse(
        text: String,
        receivedAt: LocalDateTime,
    ): ParsedMessage
}

object ParserDates {
    /**
     * 연도가 없는 MM/dd HH:mm을 수신 시점 기준으로 보정한다.
     * 수신일보다 하루 이상 미래면 전년도로 간주한다 (12월 말 수신한 1월 거래 등 경계 대응).
     */
    fun resolveYear(
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        receivedAt: LocalDateTime,
    ): LocalDateTime {
        val candidate = LocalDateTime.of(receivedAt.year, month, day, hour, minute)
        return if (candidate.isAfter(receivedAt.plusDays(1))) candidate.minusYears(1) else candidate
    }
}
