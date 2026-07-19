package com.toy.backend.ledger.recurring

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import com.toy.backend.ledger.entries.EntrySource
import com.toy.backend.ledger.entries.LedgerEntry
import com.toy.backend.ledger.entries.LedgerEntryRepository
import com.toy.backend.user.User
import com.toy.backend.user.UserRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val log = KotlinLogging.logger {}

@Service
@Transactional(readOnly = true)
class RecurringRuleService(
    private val repository: RecurringRuleRepository,
    private val entryRepository: LedgerEntryRepository,
    private val userRepository: UserRepository,
) {
    fun list(username: String): List<RecurringRuleResponse> = repository.findAllByUser(findUser(username)).map { it.toResponse() }

    /** 내역 상세의 "반복" 버튼: entry 값을 복사해 규칙을 만든다. */
    @Transactional
    fun create(
        username: String,
        request: RecurringRuleCreateRequest,
    ): Long {
        val user = findUser(username)
        val entry =
            entryRepository.findByIdOrNull(request.entryId)
                ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, request.entryId)
        if (entry.user.requiredId != user.requiredId) {
            throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, request.entryId)
        }
        val rule =
            RecurringRule(
                user = user,
                dayOfMonth = request.dayOfMonth ?: entry.entryAt.dayOfMonth,
                amount = entry.amount,
                currency = entry.currency,
                type = entry.type,
                merchant = entry.merchant,
                description = entry.description,
            )
        return repository.save(rule).requiredId
    }

    @Transactional
    fun update(
        username: String,
        id: Long,
        request: RecurringRuleUpdateRequest,
    ) {
        val rule = authorizedRule(findUser(username), id)
        rule.updateDetails(
            dayOfMonth = request.dayOfMonth,
            amount = request.amount,
            currency = request.currency,
            type = request.type,
            merchant = request.merchant,
            description = request.description,
            active = request.active,
        )
    }

    @Transactional
    fun delete(
        username: String,
        id: Long,
    ) {
        val rule = authorizedRule(findUser(username), id)
        repository.delete(rule)
    }

    /**
     * 반복일이 지났는데 이번 달 미생성인 규칙의 entry를 생성한다.
     * "<= today" 조건이라 서버가 며칠 중단돼도 다음 실행에서 밀린 건이 생성된다(캐치업).
     * 해당 월에 없는 날짜(예: 31일)는 말일로 보정한다.
     */
    @Transactional
    fun generateDueEntries(today: LocalDate): Int {
        val currentMonth = today.format(MONTH_FORMAT)
        var created = 0
        repository.findAllByActiveTrue().forEach { rule ->
            val effectiveDay = minOf(rule.dayOfMonth, today.lengthOfMonth())
            if (today.dayOfMonth < effectiveDay || rule.lastGeneratedMonth == currentMonth) return@forEach

            entryRepository.save(
                LedgerEntry(
                    user = rule.user,
                    entryAt = today.withDayOfMonth(effectiveDay).atStartOfDay(),
                    amount = rule.amount,
                    currency = rule.currency,
                    type = rule.type,
                    merchant = rule.merchant,
                    description = rule.description,
                    source = EntrySource.RECURRING,
                ),
            )
            rule.lastGeneratedMonth = currentMonth
            created++
        }
        if (created > 0) log.info { "반복 내역 생성: $created 건 ($currentMonth)" }
        return created
    }

    private fun authorizedRule(
        user: User,
        id: Long,
    ): RecurringRule {
        val rule =
            repository.findByIdOrNull(id)
                ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        if (rule.user.requiredId != user.requiredId) {
            throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        }
        return rule
    }

    private fun findUser(username: String): User =
        userRepository.findByUsername(username)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)

    companion object {
        private val MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM")
    }
}
