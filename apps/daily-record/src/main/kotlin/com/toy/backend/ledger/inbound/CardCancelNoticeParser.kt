package com.toy.backend.ledger.inbound

import com.toy.backend.common.exception.CustomException
import com.toy.backend.ledger.LedgerErrorCode
import com.toy.backend.ledger.entries.EntrySource
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * PG를 거친 결제의 **취소 알림** 파서. 한 줄로 온다.
 *
 * ```
 * [Web발신]
 * [현대카드] 이*지님 08/10 (주)이니시스 - (주)공영홈쇼핑 사용 22,320원 취소처리되었습니다
 * ```
 *
 * **`CardApprovalParser`가 다루는 취소와 형식이 다르다.** 그쪽은 여러 줄에 시각까지 붙은
 * `대한항공카드 취소`인데, 이쪽은 한 줄이고 시각이 없으며 가맹점 앞에 PG사가 붙는다.
 *
 * **승인 쪽은 이 형식으로 오지 않는다.** 2026-08-10 같은 결제의 승인은 여러 줄짜리 일반
 * 승인 문자로 왔고(`대한항공카드 승인 … 08/10 18:21 … (주)공영홈쇼핑`), 취소만 이 형식이었다.
 * 그래서 이 파서는 취소만 낸다 — 승인 버전이 확인되면 그때 [KIND] 자리를 넓히면 된다.
 */
@Component
@Order(15)
class CardCancelNoticeParser : MessageParser {
    override fun supports(text: String): Boolean = LINE_REGEX.containsMatchIn(text)

    override fun parse(
        text: String,
        receivedAt: LocalDateTime,
    ): ParsedMessage {
        val match =
            LINE_REGEX.find(text)
                ?: throw CustomException(LedgerErrorCode.MESSAGE_PARSE_FAILED, "취소 알림 형식 아님")
        val (month, day, rawMerchant, amount) = match.destructured

        return ParsedMessage(
            kind = ParsedKind.CANCEL,
            amount = BigDecimal(amount.replace(",", "")),
            currency = "KRW",
            merchant = rawMerchant.substringAfter(PG_SEPARATOR).trim(),
            occurredAt =
                ParserDates
                    .resolveYear(month.toInt(), day.toInt(), 0, 0, receivedAt)
                    .toLocalDate()
                    .atTime(LocalTime.MAX),
            source = EntrySource.SMS,
        )
    }

    companion object {
        /**
         * **PG사와 실가맹점을 가르는 구분자.** 문자에는 `(주)이니시스 - (주)공영홈쇼핑`처럼
         * PG사가 앞에 붙어 오는데, 같은 결제의 승인 문자는 실가맹점(`(주)공영홈쇼핑`)만
         * 싣는다. 떼지 않으면 가맹점이 달라 **취소 매칭이 실패하고 음수 건이 따로 쌓인다**
         * (`InboundService.cancel`은 가맹점이 정확히 같은 승인 건만 찾는다).
         *
         * `substringAfter`는 구분자가 없으면 원본을 그대로 준다 — PG를 안 거친 결제는
         * 가맹점이 하나만 오므로 그때는 자르지 않는다. **뒤에서부터 찾지 않는 이유**는
         * 가맹점 이름 자체에 ` - `가 들어 있을 때 이름을 잘라 먹기 때문이다. PG는 늘 맨
         * 앞이므로 앞에서 한 번만 자른다.
         */
        private const val PG_SEPARATOR = " - "

        /**
         * `08/10 (주)이니시스 - (주)공영홈쇼핑 사용 22,320원 취소처리` 를 한 번에 뽑는다.
         *
         * 가맹점을 `.+?`(최소 일치)로 두고 `사용`을 앵커로 쓴다 — 가맹점 이름에 공백이 들어
         * 있어도(`(주)이니시스 - (주)공영홈쇼핑`) 「사용」 앞까지만 가져온다.
         *
         * **`취소처리`까지 요구한다.** 이것이 없으면 승인 알림이 같은 모양으로 올 때
         * 취소로 저장된다 — 금액이 반대로 뒤집히는 쪽이라 합계가 조용히 틀어진다.
         */
        private val LINE_REGEX =
            Regex("""(\d{2})/(\d{2})\s+(.+?)\s+사용\s+([\d,]+)원\s*취소처리""")
    }
}
