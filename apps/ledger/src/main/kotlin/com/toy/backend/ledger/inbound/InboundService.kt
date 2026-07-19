package com.toy.backend.ledger.inbound

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import com.toy.backend.ledger.entries.EntryType
import com.toy.backend.ledger.entries.LedgerEntry
import com.toy.backend.ledger.entries.LedgerEntryRepository
import com.toy.backend.user.User
import com.toy.backend.user.UserRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

private val log = KotlinLogging.logger {}

@Service
class InboundService(
    private val parsers: List<MessageParser>,
    private val entryRepository: LedgerEntryRepository,
    private val inboundRepository: InboundMessageRepository,
    private val userRepository: UserRepository,
) {
    @Transactional
    fun process(
        username: String,
        text: String,
    ): InboundResponse {
        val user = findUser(username)
        val receivedAt = LocalDateTime.now()

        val parser = parsers.firstOrNull { it.supports(text) }
        if (parser == null) {
            log.warn { "파싱 가능한 파서 없음: user=$username, length=${text.length}" }
            return record(user, text, InboundStatus.PARSE_FAILED, entryId = null)
        }

        return runCatching {
            val parsed = parser.parse(text, receivedAt)
            when (parsed.kind) {
                ParsedKind.APPROVAL -> {
                    val entry = entryRepository.save(parsed.toEntry(user))
                    record(user, text, InboundStatus.SAVED, entry.requiredId)
                }

                ParsedKind.CANCEL -> {
                    cancel(user, text, parsed, receivedAt)
                }
            }
        }.getOrElse { e ->
            log.error(e) { "파싱 실패: user=$username, parser=${parser::class.simpleName}, length=${text.length}" }
            record(user, text, InboundStatus.PARSE_FAILED, entryId = null)
        }
    }

    /**
     * 같은 금액·통화·가맹점의 최근 7일 내 승인 건을 삭제하고, 없으면 음수 건으로 저장해 합계를 보정한다.
     * 취소 문자와 같은 source(SMS)로 저장된 건만 매칭한다 — 수동/반복/이관 건이 삭제되는 것을 막는다.
     */
    private fun cancel(
        user: User,
        text: String,
        parsed: ParsedMessage,
        receivedAt: LocalDateTime,
    ): InboundResponse {
        val matched =
            parsed.merchant?.let { merchant ->
                entryRepository.findLatestCancellable(
                    user = user,
                    amount = parsed.amount,
                    currency = parsed.currency,
                    merchant = merchant,
                    source = parsed.source,
                    after = receivedAt.minusDays(CANCEL_MATCH_DAYS),
                )
            }
        if (matched != null) {
            entryRepository.delete(matched)
            return record(user, text, InboundStatus.CANCEL_MATCHED, matched.requiredId)
        }
        val negated = entryRepository.save(parsed.copy(amount = parsed.amount.negate()).toEntry(user))
        return record(user, text, InboundStatus.SAVED, negated.requiredId)
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

    private fun record(
        user: User,
        text: String,
        status: InboundStatus,
        entryId: Long?,
    ): InboundResponse {
        inboundRepository.save(InboundMessage(user = user, rawText = text, status = status, entryId = entryId))
        return InboundResponse(status = status, entryId = entryId)
    }

    private fun findUser(username: String): User =
        userRepository.findByUsername(username)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)

    companion object {
        private const val CANCEL_MATCH_DAYS = 7L
    }
}
