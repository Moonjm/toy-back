package com.toy.backend.ledger.inbound

import com.toy.backend.ledger.entries.EntrySource
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * 국내 카드 승인/취소 문자 파서.
 *
 * ```
 * [Web발신]
 * 대한항공카드 승인|취소
 * 문*민
 * 18,920원 일시불
 * 07/14 07:38          ← 연도 없음, 수신 시점 기준 보정
 * 제주특별자치도개발      ← 일시 다음 줄이 가맹점
 * 누적438,919원
 * ```
 */
@Component
@Order(10)
class CardApprovalParser : MessageParser {
    override fun supports(text: String): Boolean {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return KIND_REGEX.containsMatchIn(text) &&
            lines.any { AMOUNT_LINE_REGEX.containsMatchIn(it) } &&
            lines.any { DATE_REGEX.containsMatchIn(it) }
    }

    override fun parse(
        text: String,
        receivedAt: LocalDateTime,
    ): ParsedMessage {
        val kind = if (KIND_REGEX.find(text)!!.groupValues[1] == "취소") ParsedKind.CANCEL else ParsedKind.APPROVAL

        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val amount =
            BigDecimal(
                lines.firstNotNullOfOrNull { AMOUNT_LINE_REGEX.find(it) }!!.groupValues[1].replace(",", ""),
            )

        val dateIndex = lines.indexOfFirst { DATE_REGEX.containsMatchIn(it) }
        val dateMatch = DATE_REGEX.find(lines[dateIndex])!!
        val (month, day, hour, minute) = dateMatch.destructured
        val merchant =
            lines
                .getOrNull(dateIndex + 1)
                ?.takeUnless { it.startsWith("누적") }

        return ParsedMessage(
            kind = kind,
            amount = amount,
            currency = "KRW",
            merchant = merchant,
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
        private val KIND_REGEX = Regex("""카드\s*(승인|취소)""")
        private val AMOUNT_LINE_REGEX = Regex("""^([\d,]+)원""")
        private val DATE_REGEX = Regex("""(\d{2})/(\d{2})\s+(\d{2}):(\d{2})""")
    }
}
