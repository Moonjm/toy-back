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
            merchant = stripPgPrefix(rawMerchant),
            occurredAt =
                ParserDates
                    .resolveYear(month.toInt(), day.toInt(), 0, 0, receivedAt)
                    .toLocalDate()
                    .atTime(END_OF_DAY),
            source = EntrySource.SMS,
        )
    }

    /**
     * 가맹점 이름 앞의 PG사를 뗀다. **앞이 PG로 확인된 것일 때만 뗀다.**
     *
     * 「구분자가 있으면 무조건 앞을 버린다」로 두면 `공차 - 강남점`처럼 이름 자체에 ` - `가
     * 든 가맹점이 `강남점`이 되어, 전체 이름으로 저장된 승인 건과 어긋난다 — 매칭이 실패해
     * 승인 건이 남은 채 음수 건이 따로 쌓인다.
     *
     * 모르는 PG는 떼지 않는다. 그래도 결과는 「매칭 실패 → 음수 건」이라 합계는 맞고,
     * 잘못 떼는 쪽과 대가가 같다. 새 PG가 보이면 [PG_NAMES]에 더하면 된다.
     */
    private fun stripPgPrefix(rawMerchant: String): String {
        val head = rawMerchant.substringBefore(PG_SEPARATOR, missingDelimiterValue = "")
        if (head.isEmpty()) return rawMerchant.trim()
        return if (PG_NAMES.any { head.contains(it, ignoreCase = true) }) {
            rawMerchant.substringAfter(PG_SEPARATOR).trim()
        } else {
            rawMerchant.trim()
        }
    }

    companion object {
        /**
         * **PG사와 실가맹점을 가르는 구분자.** 문자에는 `(주)이니시스 - (주)공영홈쇼핑`처럼
         * PG사가 앞에 붙어 오는데, 같은 결제의 승인 문자는 실가맹점(`(주)공영홈쇼핑`)만
         * 싣는다. 떼지 않으면 가맹점이 달라 **취소 매칭이 실패하고 음수 건이 따로 쌓인다**
         * (`InboundService.cancel`은 가맹점이 정확히 같은 승인 건만 찾는다).
         *
         * 앞에서 한 번만 자른다 — PG는 맨 앞에 온다.
         */
        private const val PG_SEPARATOR = " - "

        /**
         * 구분자 앞이 PG사인지 가리는 이름들. **실측된 것은 `이니시스`뿐이고**(2026-08-19),
         * 나머지는 국내에서 널리 쓰이는 PG다. 여기 없는 PG는 떼지 않는다 — [stripPgPrefix]
         * 참고.
         */
        private val PG_NAMES = listOf("이니시스", "토스페이먼츠", "나이스페이", "KCP", "다날")

        /**
         * 하루의 끝. **`LocalTime.MAX`를 쓰면 안 된다.**
         *
         * `LocalTime.MAX`는 `23:59:59.999999999`(나노초)인데 PostgreSQL의 `timestamp`는
         * 마이크로초까지만 담는다. JDBC 드라이버가 반올림해 **다음 날 `00:00:00`으로 저장한다**
         * (실측 2026-08-19: `2026-08-10T23:59:59.999999999` → `2026-08-11T00:00`).
         *
         * 그러면 둘이 함께 틀어진다 — 매칭에 실패한 취소 건이 **하루 뒤 날짜로 장부에 남고**,
         * 매칭 창(`entryAt <= :before`)이 **다음 날 자정 승인 건까지** 끌어온다.
         *
         * **목으로 리포지토리를 대체하는 단위 테스트는 이 변환을 드러내지 못한다**
         * (`AGENTS.md`의 「단위 테스트는 …DB 제약 문제를 잡지 못한다」).
         */
        private val END_OF_DAY: LocalTime = LocalTime.of(23, 59, 59, 999_999_000)

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
