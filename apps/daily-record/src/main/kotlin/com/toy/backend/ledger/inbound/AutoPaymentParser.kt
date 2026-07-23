package com.toy.backend.ledger.inbound

import com.toy.backend.common.exception.CustomException
import com.toy.backend.ledger.LedgerErrorCode
import com.toy.backend.ledger.entries.EntrySource
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * 카드 자동납부 승인 문자 파서.
 *
 * ```
 * [Web발신]
 * [현대카드] 자동납부 승인 문*민님 SK브로드밴드 34,100원
 * ```
 *
 * 일시가 없으므로 수신 시점을 발생 시각으로 쓴다.
 * 자동납부는 취소 문자가 오지 않는 형식이라 승인만 처리한다.
 */
@Component
@Order(40)
class AutoPaymentParser : MessageParser {
    override fun supports(text: String): Boolean = LINE_REGEX.containsMatchIn(text)

    override fun parse(
        text: String,
        receivedAt: LocalDateTime,
    ): ParsedMessage {
        val match =
            LINE_REGEX.find(text)
                ?: throw CustomException(LedgerErrorCode.MESSAGE_PARSE_FAILED, "자동납부 승인 문구 없음")
        val (merchant, amount) = match.destructured
        return ParsedMessage(
            kind = ParsedKind.APPROVAL,
            amount = BigDecimal(amount.replace(",", "")),
            currency = "KRW",
            merchant = merchant.trim(),
            occurredAt = receivedAt,
            source = EntrySource.SMS,
        )
    }

    companion object {
        // "자동납부 승인 {이름}님 {가맹점} {금액}원" — 이름과 금액 사이 텍스트가 가맹점
        private val LINE_REGEX = Regex("""자동납부\s*승인\s+\S+님\s+(.+?)\s*([\d,]+)원""")
    }
}
