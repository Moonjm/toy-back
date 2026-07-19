package com.toy.backend.ledger.inbound

import com.toy.backend.common.exception.CustomException
import com.toy.backend.ledger.LedgerErrorCode
import com.toy.backend.ledger.entries.EntrySource
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * 카카오페이 알림톡 파서. 라벨 앵커 방식이라 OCR 노이즈(상단 헤더, 하단 버튼)에 강하다.
 *
 * 입력 경로 두 가지:
 * 1. 알림톡 캡쳐 → iOS 단축어 OCR 텍스트 — "결제금액\nN원" 헤더 포함
 * 2. 카톡 본문 복붙 — 금액 헤더가 없으므로 amount = 0으로 저장하고 사용자가 수정
 */
@Component
@Order(30)
class KakaoPayParser : MessageParser {
    override fun supports(text: String): Boolean = MERCHANT_REGEX.containsMatchIn(text) && DATE_REGEX.containsMatchIn(text)

    override fun parse(
        text: String,
        receivedAt: LocalDateTime,
    ): ParsedMessage {
        val amount =
            AMOUNT_REGEX.find(text)?.let { BigDecimal(it.groupValues[1].replace(",", "")) }
                ?: BigDecimal.ZERO

        val merchantMatch = MERCHANT_REGEX.find(text) ?: throw CustomException(LedgerErrorCode.MESSAGE_PARSE_FAILED, "구매처 라벨 없음")
        val merchant = merchantMatch.groupValues[1].trim()

        val dateMatch = DATE_REGEX.find(text) ?: throw CustomException(LedgerErrorCode.MESSAGE_PARSE_FAILED, "결제일시 없음")
        val (year, month, day, hour, minute) = dateMatch.destructured

        return ParsedMessage(
            kind = ParsedKind.APPROVAL,
            amount = amount,
            currency = "KRW",
            merchant = merchant,
            occurredAt =
                LocalDateTime.of(
                    year.toInt(),
                    month.toInt(),
                    day.toInt(),
                    hour.toInt(),
                    minute.toInt(),
                ),
            source = EntrySource.KAKAO_PAY,
            description = extractProductName(text),
        )
    }

    /** "- 상품명:" 라벨부터 다음 "- " 라벨 전까지의 줄을 이어붙인다 (OCR/복붙 줄바꿈 대응). */
    private fun extractProductName(text: String): String? {
        val lines = text.lines().map { it.trim() }
        val startIndex = lines.indexOfFirst { PRODUCT_REGEX.containsMatchIn(it) }
        val firstPart =
            lines
                .getOrNull(startIndex)
                ?.let { PRODUCT_REGEX.find(it) }
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?: return null

        val parts = mutableListOf(firstPart)
        for (i in startIndex + 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty() || line.startsWith("- ") || line.startsWith("*")) break
            parts += line
        }
        return parts.joinToString(" ").take(500)
    }

    companion object {
        private val AMOUNT_REGEX = Regex("""결제금액[\s\n]*([\d,]+)\s*원""")
        private val MERCHANT_REGEX = Regex("""-\s*구매처\s*:\s*(.+)""")
        private val PRODUCT_REGEX = Regex("""-\s*상품명\s*:\s*(.+)""")
        private val DATE_REGEX = Regex("""-\s*결제일시\s*:\s*(\d{4})\.(\d{2})\.(\d{2})\s+(\d{1,2}):(\d{2})""")
    }
}
