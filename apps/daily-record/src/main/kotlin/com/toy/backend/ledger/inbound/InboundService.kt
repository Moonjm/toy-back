package com.toy.backend.ledger.inbound

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import com.toy.backend.ledger.LedgerErrorCode
import com.toy.backend.ledger.entries.EntryType
import com.toy.backend.ledger.entries.LedgerEntry
import com.toy.backend.ledger.entries.LedgerEntryRepository
import com.toy.backend.user.User
import com.toy.backend.user.UserRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.text.DecimalFormat
import java.time.LocalDateTime

private val log = KotlinLogging.logger {}

@Service
class InboundService(
    private val parsers: List<MessageParser>,
    private val entryRepository: LedgerEntryRepository,
    private val inboundRepository: InboundMessageRepository,
    private val userRepository: UserRepository,
    private val transactionTemplate: TransactionTemplate,
    private val fxRateClient: FxRateClient,
) {
    /**
     * 수신 원문을 파싱해 내역으로 저장하고, 처리 결과와 함께 원문을 기록한다.
     * 내역 저장은 별도 트랜잭션(TransactionTemplate)에서 수행하므로, 저장이 실패해도
     * rollback-only 오염 없이 PARSE_FAILED 원문 기록이 남아 [retry]로 되살릴 수 있다.
     * (문자는 카드사가 재발송해 주지 않으므로 원문 보존이 최우선이다)
     *
     * @return 저장된 수신 기록의 id — 컨트롤러가 Location(/inbound/{id}/retry)으로 노출한다.
     */
    fun process(
        username: String,
        text: String,
    ): Long {
        val user = findUser(username)
        val outcome = handle(user, text)
        val message =
            inboundRepository.save(
                InboundMessage(user = user, rawText = text, status = outcome.status, entryId = outcome.entryId),
            )
        return message.requiredId
    }

    /**
     * PARSE_FAILED로 보존된 원문을 다시 처리하고 수신 로그의 상태를 갱신한다(파서 수정 후 복구용).
     * 재처리도 실패하면 상태를 그대로 두고 MESSAGE_PARSE_FAILED(400)를 던진다 —
     * 성공/실패는 HTTP 상태코드(204/400)로 구분된다.
     */
    fun retry(
        username: String,
        id: Long,
    ) {
        val user = findUser(username)
        val message =
            inboundRepository.findByIdAndUser(id, user)
                ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        if (message.status != InboundStatus.PARSE_FAILED) {
            throw CustomException(LedgerErrorCode.INBOUND_NOT_RETRYABLE, id)
        }

        val outcome = handle(user, message.rawText)
        if (outcome.status == InboundStatus.PARSE_FAILED) {
            throw CustomException(LedgerErrorCode.MESSAGE_PARSE_FAILED, id)
        }
        message.status = outcome.status
        message.entryId = outcome.entryId
        inboundRepository.save(message)
    }

    /** 파싱~내역 저장을 자체 트랜잭션에서 수행하고, 어떤 실패든 PARSE_FAILED로 흡수한다. */
    private fun handle(
        user: User,
        text: String,
    ): Outcome {
        val receivedAt = LocalDateTime.now()
        val parser = parsers.firstOrNull { it.supports(text) }
        if (parser == null) {
            log.warn { "파싱 가능한 파서 없음: user=${user.username}, length=${text.length}" }
            return Outcome(InboundStatus.PARSE_FAILED, entryId = null)
        }

        return runCatching {
            checkNotNull(
                transactionTemplate.execute {
                    val parsed = parser.parse(text, receivedAt)
                    when (parsed.kind) {
                        ParsedKind.APPROVAL -> {
                            Outcome(InboundStatus.SAVED, entryRepository.save(parsed.toEntry(user)).requiredId)
                        }

                        ParsedKind.CANCEL -> {
                            cancel(user, parsed)
                        }
                    }
                },
            )
        }.getOrElse { e ->
            log.error(e) { "수신 처리 실패(원문은 보존됨): user=${user.username}, parser=${parser::class.simpleName}, length=${text.length}" }
            Outcome(InboundStatus.PARSE_FAILED, entryId = null)
        }
    }

    /**
     * 같은 금액·통화·가맹점의 승인 건 중 취소 시각 이전 7일 내 최신 건을 삭제하고,
     * 없으면 음수 건으로 저장해 합계를 보정한다.
     * 창의 기준이 수신(재처리) 시각이 아니라 파싱된 취소 시각이므로, 실패 건을 나중에
     * 재처리해도 취소 이후에 발생한 새 승인이 삭제되지 않는다.
     * 취소 문자와 같은 source(SMS)로 저장된 건만 매칭한다 — 수동/반복/이관 건이 삭제되는 것을 막는다.
     */
    private fun cancel(
        user: User,
        parsed: ParsedMessage,
    ): Outcome {
        val matched =
            // 저장 시 컬럼 길이로 절단하므로(toEntry) 조회 키도 동일하게 정규화해야 매칭된다.
            parsed.merchant?.take(MERCHANT_MAX_LENGTH)?.let { merchant ->
                entryRepository.findLatestCancellable(
                    user = user,
                    amount = parsed.amount,
                    currency = parsed.currency,
                    merchant = merchant,
                    source = parsed.source,
                    after = parsed.occurredAt.minusDays(CANCEL_MATCH_DAYS),
                    before = parsed.occurredAt,
                )
            }
        if (matched != null) {
            entryRepository.delete(matched)
            return Outcome(InboundStatus.CANCEL_MATCHED, matched.requiredId)
        }
        val negated = entryRepository.save(parsed.copy(amount = parsed.amount.negate()).toEntry(user))
        return Outcome(InboundStatus.SAVED, negated.requiredId)
    }

    private fun ParsedMessage.toEntry(user: User): LedgerEntry =
        LedgerEntry(
            user = user,
            entryAt = occurredAt,
            amount = amount,
            currency = currency,
            type = EntryType.EXPENSE,
            merchant = merchant?.take(MERCHANT_MAX_LENGTH),
            description =
                listOfNotNull(description, fxNote())
                    .joinToString(" · ")
                    .ifEmpty { null }
                    ?.take(DESCRIPTION_MAX_LENGTH),
            source = source,
        )

    /**
     * 외화 결제면 결제 시점 환율과 원화 환산액을 메모로 남긴다.
     * 예) "환율 1 JPY ≈ 9.15원 (약 9,150원)". 조회 실패 시 null — 저장을 막지 않는다.
     */
    private fun ParsedMessage.fxNote(): String? {
        if (currency.uppercase() == "KRW") return null
        val rate = fxRateClient.rateToKrw(currency) ?: return null
        val converted = amount.abs().multiply(rate).setScale(0, java.math.RoundingMode.HALF_UP)
        val rateText = DecimalFormat("#,##0.##").format(rate)
        val convertedText = DecimalFormat("#,##0").format(converted)
        return "환율 1 ${currency.uppercase()} ≈ ${rateText}원 (약 ${convertedText}원)"
    }

    private fun findUser(username: String): User =
        userRepository.findByUsername(username)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)

    private data class Outcome(
        val status: InboundStatus,
        val entryId: Long?,
    )

    companion object {
        private const val CANCEL_MATCH_DAYS = 7L

        /** ledger_entries.merchant 컬럼 길이 — 저장·조회 양쪽에 동일하게 적용한다. */
        private const val MERCHANT_MAX_LENGTH = 100

        /** ledger_entries.description 컬럼 길이 — 환율 메모를 붙여도 넘지 않게 절단한다. */
        private const val DESCRIPTION_MAX_LENGTH = 500
    }
}
