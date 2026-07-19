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
import org.springframework.data.repository.findByIdOrNull
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
     * 수신 원문을 파싱해 내역으로 저장하고, 처리 결과와 무관하게 원문을 항상 보존한다.
     * 내역 저장은 별도 트랜잭션(TransactionTemplate)에서 수행하므로 저장이 실패해도
     * rollback-only 오염 없이 PARSE_FAILED 원문 기록이 남고, 이후 [retry]로 재처리할 수 있다.
     * (문자는 카드사가 재발송해 주지 않으므로 원문 보존이 최우선이다)
     */
    fun process(
        username: String,
        text: String,
    ): InboundResponse {
        val user = findUser(username)
        val outcome = handle(user, text)
        inboundRepository.save(
            InboundMessage(user = user, rawText = text, status = outcome.status, entryId = outcome.entryId),
        )
        return InboundResponse(status = outcome.status, entryId = outcome.entryId)
    }

    /** PARSE_FAILED로 보존된 원문을 다시 처리하고, 해당 수신 로그의 상태를 결과로 갱신한다. */
    fun retry(
        username: String,
        id: Long,
    ): InboundResponse {
        val user = findUser(username)
        val message =
            inboundRepository.findByIdOrNull(id)
                ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        if (message.user.requiredId != user.requiredId) {
            throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        }
        if (message.status != InboundStatus.PARSE_FAILED) {
            throw CustomException(LedgerErrorCode.INBOUND_NOT_RETRYABLE, id)
        }
        val outcome = handle(user, message.rawText)
        message.status = outcome.status
        message.entryId = outcome.entryId
        inboundRepository.save(message)
        return InboundResponse(status = outcome.status, entryId = outcome.entryId)
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
            parsed.merchant?.let { merchant ->
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
            merchant = merchant?.take(100),
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
    }
}
