package com.toy.backend.ledger.inbound

import com.toy.backend.ledger.entries.EntrySource
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * 해외 승인 문자 파서. 통화·금액을 환산 없이 그대로 추출한다.
 *
 * ```
 * [Web발신]
 * [현대카드] 해외승인
 * 문*민님
 * 07/14 23:15
 * JPY 1,000.00         ← 통화 + 금액
 * SUICAMOBILEPAYMENT   ← 금액 다음 줄이 가맹점
 * ```
 */
@Component
@Order(20)
class OverseasApprovalParser : MessageParser {
    override fun supports(text: String): Boolean = text.contains("해외승인") && AMOUNT_REGEX.containsMatchIn(text)

    override fun parse(
        text: String,
        receivedAt: LocalDateTime,
    ): ParsedMessage {
        val kind = if (text.contains("취소")) ParsedKind.CANCEL else ParsedKind.APPROVAL

        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val amountIndex = lines.indexOfFirst { AMOUNT_REGEX.matches(it) }
        val amountMatch = AMOUNT_REGEX.find(lines[amountIndex])!!
        val (currency, rawAmount) = amountMatch.destructured

        val dateMatch = DATE_REGEX.find(text)!!
        val (month, day, hour, minute) = dateMatch.destructured

        return ParsedMessage(
            kind = kind,
            amount = BigDecimal(rawAmount.replace(",", "")),
            currency = currency,
            merchant = lines.getOrNull(amountIndex + 1),
            occurredAt =
                ParserDates.resolveYear(
                    month.toInt(),
                    day.toInt(),
                    hour.toInt(),
                    minute.toInt(),
                    receivedAt,
                ),
            source = EntrySource.SMS,
        )
    }

    companion object {
        private val AMOUNT_REGEX = Regex("""([A-Z]{3})\s+([\d,]+(?:\.\d+)?)""")
        private val DATE_REGEX = Regex("""(\d{2})/(\d{2})\s+(\d{2}):(\d{2})""")
    }
}
