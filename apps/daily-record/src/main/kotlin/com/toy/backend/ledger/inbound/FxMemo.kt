package com.toy.backend.ledger.inbound

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat

/**
 * 외화 결제의 환율 메모("환율 1 JPY ≈ 9.1원 (약 9,150원)") 생성·제거.
 * 수신 저장(InboundService)과 내역 수정(LedgerEntryService)이 같은 형식을 쓰도록 한 곳에서 관리한다.
 */
object FxMemo {
    const val SEPARATOR = " · "

    private val MEMO_REGEX = Regex("""환율 1 [A-Z]{3} ≈ [\d,.]+원 \(약 [\d,]+원\)""")

    private val CONVERTED_REGEX = Regex("""\(약 ([\d,]+)원\)""")

    fun build(
        currency: String,
        amount: BigDecimal,
        rate: BigDecimal,
    ): String {
        val converted = amount.abs().multiply(rate).setScale(0, RoundingMode.HALF_UP)
        val rateText = DecimalFormat("#,##0.##").format(rate)
        val convertedText = DecimalFormat("#,##0").format(converted)
        return "환율 1 ${currency.uppercase()} ≈ ${rateText}원 (약 ${convertedText}원)"
    }

    /**
     * description의 환율 메모에서 원화 환산액을 꺼낸다. 메모가 없으면 null.
     * 메모의 환산액은 절대값이므로 취소(음수) 건은 호출한 쪽에서 부호를 입혀야 한다.
     */
    fun convertedKrw(description: String?): BigDecimal? {
        description ?: return null
        val memo = MEMO_REGEX.find(description)?.value ?: return null
        val text = CONVERTED_REGEX.find(memo)?.groupValues?.get(1) ?: return null
        return BigDecimal(text.replace(",", ""))
    }

    /** description에서 환율 메모 조각을 제거한다. 메모뿐이었다면 null. */
    fun strip(description: String?): String? =
        description
            ?.split(SEPARATOR)
            ?.filterNot { MEMO_REGEX.matches(it.trim()) }
            ?.joinToString(SEPARATOR)
            ?.ifBlank { null }

    /**
     * 기본 메모와 환율 메모를 길이 제한 안에서 합친다.
     * 환율 메모가 중간에 잘리면 strip()이 인식하지 못해 낡은 조각이 남으므로,
     * 기본 메모를 먼저 줄여 환율 메모가 항상 온전히 들어가게 한다.
     */
    fun compose(
        base: String?,
        memo: String?,
        maxLength: Int,
    ): String? {
        if (memo == null) return base?.take(maxLength)?.ifBlank { null }
        val room = maxLength - memo.length - SEPARATOR.length
        val trimmedBase = base?.take(room.coerceAtLeast(0))?.ifBlank { null }
        return listOfNotNull(trimmedBase, memo).joinToString(SEPARATOR).take(maxLength)
    }
}
