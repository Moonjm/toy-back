package com.toy.backend.ledger.inbound

import com.toy.backend.common.exception.CustomException
import com.toy.backend.ledger.LedgerErrorCode
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
 * 대한항공카드 승인|취소     ← 「현대 대한항공030 승인」처럼 「카드」가 없기도 하다
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
        return !text.contains(OVERSEAS_MARKER) &&
            lines.any { KIND_LINE_REGEX.containsMatchIn(it) } &&
            lines.any { AMOUNT_LINE_REGEX.containsMatchIn(it) } &&
            lines.any { DATE_REGEX.containsMatchIn(it) }
    }

    override fun parse(
        text: String,
        receivedAt: LocalDateTime,
    ): ParsedMessage {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val kindMatch =
            lines.firstNotNullOfOrNull { KIND_LINE_REGEX.find(it) }
                ?: throw CustomException(LedgerErrorCode.MESSAGE_PARSE_FAILED, "승인/취소 문구 없음")
        val kind = if (kindMatch.groupValues[1] == "취소") ParsedKind.CANCEL else ParsedKind.APPROVAL

        val amountMatch =
            lines.firstNotNullOfOrNull { AMOUNT_LINE_REGEX.find(it) }
                ?: throw CustomException(LedgerErrorCode.MESSAGE_PARSE_FAILED, "금액 줄 없음")
        val amount = BigDecimal(amountMatch.groupValues[1].replace(",", ""))

        val dateIndex = lines.indexOfFirst { DATE_REGEX.containsMatchIn(it) }
        val dateMatch =
            lines.getOrNull(dateIndex)?.let { DATE_REGEX.find(it) }
                ?: throw CustomException(LedgerErrorCode.MESSAGE_PARSE_FAILED, "일시 줄 없음")
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
        /**
         * `OverseasApprovalParser`(`@Order(20)`)의 표식. **이 파서가 `@Order(10)`으로 먼저
         * 물어보므로 여기서 명시적으로 넘겨야 한다** — 가로채면 통화·환율 처리가 통째로 빠진다.
         *
         * 지금은 `AMOUNT_LINE_REGEX`(`^숫자원`)가 `JPY 1,000.00`을 걸러 주기도 하지만, 그 조건이
         * 나중에 느슨해지면 해외승인이 조용히 이쪽으로 넘어온다. 저쪽이 이 문자열로 자기 것을
         * 고르므로 여기서도 같은 문자열로 배제한다.
         */
        private const val OVERSEAS_MARKER = "해외승인"

        /**
         * **「승인」·「취소」로 끝나는 줄**을 찾는다. 카드사 이름·상품명은 보지 않는다.
         *
         * 처음에는 `카드\s*(승인|취소)`로 「카드」를 리터럴로 요구했는데, 그것이 문자에 늘
         * 있다고 본 것이 틀렸다 — 발급사와 상품명이 갈라진 「현대 대한항공030 승인」에는
         * 어디에도 「카드」가 없다. 2026-08-19에 세 건이 이 형식으로 `PARSE_FAILED`가 됐다.
         *
         * 줄 끝으로 못 박는 이유는 다른 파서의 몫을 가로채지 않기 위해서다 — 자동납부
         * (`[현대카드] 자동납부 승인 문*민님 SK브로드밴드 34,100원`)는 「승인」이 줄 가운데라
         * 여기 걸리지 않는다.
         */
        private val KIND_LINE_REGEX = Regex("""(승인|취소)$""")
        private val AMOUNT_LINE_REGEX = Regex("""^([\d,]+)원""")
        private val DATE_REGEX = Regex("""(\d{2})/(\d{2})\s+(\d{2}):(\d{2})""")
    }
}
