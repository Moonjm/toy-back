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
import java.time.LocalDateTime

private val log = KotlinLogging.logger {}

@Service
class InboundService(
    private val parsers: List<MessageParser>,
    private val entryRepository: LedgerEntryRepository,
    private val inboundRepository: InboundMessageRepository,
    private val userRepository: UserRepository,
    private val transactionTemplate: TransactionTemplate,
) {
    /**
     * 수신 원문을 파싱해 내역으로 저장한다. 원문 기록을 PENDING으로 **먼저** 저장한 뒤 처리하므로,
     * 처리 중 프로세스가 죽거나 결과 갱신이 실패해도 커밋된 원장 변경은 항상 되짚을 원본 기록을 갖는다.
     * (문자는 카드사가 재발송해 주지 않으므로 원문 보존이 최우선이다)
     *
     * @return 저장된 수신 기록의 id — 컨트롤러가 Location(/inbound/{id}/retry)으로 노출한다.
     */
    fun process(
        username: String,
        text: String,
    ): Long {
        val user = findUser(username)
        val message =
            inboundRepository.save(
                InboundMessage(user = user, rawText = text, status = InboundStatus.PENDING),
            )
        val outcome = handle(user, text)
        applyOutcome(message, outcome)
        return message.requiredId
    }

    /**
     * PARSE_FAILED로 보존된 원문을 다시 처리하고, 수신 로그의 상태를 갱신한다.
     * 처리 전에 PARSE_FAILED → PENDING 조건부 갱신으로 대상을 선점하므로 동시 재처리 요청 중
     * 하나만 실제 처리를 수행한다. 재처리도 실패하면 PARSE_FAILED로 되돌리고
     * MESSAGE_PARSE_FAILED(400)를 던진다 — 성공/실패는 HTTP 상태코드(204/400)로 구분된다.
     */
    fun retry(
        username: String,
        id: Long,
    ) {
        val user = findUser(username)
        val message =
            inboundRepository.findByIdAndUser(id, user)
                ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        if (message.status != InboundStatus.PARSE_FAILED || inboundRepository.claimForRetry(id) == 0) {
            throw CustomException(LedgerErrorCode.INBOUND_NOT_RETRYABLE, id)
        }

        // 선점 쿼리가 영속성 컨텍스트를 비우므로(clearAutomatically) 재조회해 사용한다.
        val claimed =
            inboundRepository.findByIdAndUser(id, user)
                ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        val outcome = handle(user, claimed.rawText)
        applyOutcome(claimed, outcome)
        if (outcome.status == InboundStatus.PARSE_FAILED) {
            throw CustomException(LedgerErrorCode.MESSAGE_PARSE_FAILED, id)
        }
    }

    private fun applyOutcome(
        message: InboundMessage,
        outcome: Outcome,
    ) {
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
            description = description,
            source = source,
        )

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
    }
}
