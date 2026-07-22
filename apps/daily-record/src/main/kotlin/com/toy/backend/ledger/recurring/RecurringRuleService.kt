package com.toy.backend.ledger.recurring

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import com.toy.backend.ledger.entries.EntrySource
import com.toy.backend.ledger.entries.EntryType
import com.toy.backend.ledger.entries.LedgerEntry
import com.toy.backend.ledger.entries.LedgerEntryRepository
import com.toy.backend.user.User
import com.toy.backend.user.UserRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
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

    /**
     * 내역 상세의 "반복" 버튼: entry 값을 복사해 규칙을 만든다.
     * 원본 entry가 해당 달의 발생분이므로 lastGeneratedMonth를 entry의 달로 초기화한다 —
     * 원본과 같은 달의 중복 생성을 막으면서, 과거 달 entry로 만든 규칙은 이번 달 발생분이
     * 캐치업으로 정상 생성된다.
     */
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
        val dayOfMonth = request.dayOfMonth ?: entry.entryAt.dayOfMonth
        rejectDuplicate(
            user = user,
            dayOfMonth = dayOfMonth,
            amount = entry.amount,
            currency = entry.currency,
            type = entry.type,
            merchant = entry.merchant,
        )
        val rule =
            RecurringRule(
                user = user,
                dayOfMonth = dayOfMonth,
                amount = entry.amount,
                currency = entry.currency,
                type = entry.type,
                merchant = entry.merchant,
                description = entry.description,
                lastGeneratedMonth = entry.entryAt.format(MONTH_FORMAT),
            )
        return repository.save(rule).requiredId
    }

    @Transactional
    fun update(
        username: String,
        id: Long,
        request: RecurringRuleUpdateRequest,
    ) {
        val user = findUser(username)
        val rule = authorizedRule(user, id)
        // 수정·재활성화로 다른 활성 규칙과 같아지는 경로도 막는다(생성 검사만으로는 우회 가능).
        // 비활성으로 바꾸는 수정은 생성 대상이 아니므로 검사하지 않는다.
        if (request.active) {
            rejectDuplicate(
                user = user,
                dayOfMonth = request.dayOfMonth,
                amount = request.amount,
                currency = request.currency,
                type = request.type,
                merchant = request.merchant,
                excludeId = rule.requiredId,
            )
        }
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

    /** 오늘 생성 대상인 규칙 ID 목록. effectiveDay = min(dayOfMonth, 말일), 생성일 ≤ 오늘 && 이번 달 미생성. */
    fun findDueRuleIds(today: LocalDate): List<Long> {
        val currentMonth = today.format(MONTH_FORMAT)
        return repository
            .findAllByActiveTrue()
            .filter { rule ->
                val effectiveDay = minOf(rule.dayOfMonth, today.lengthOfMonth())
                today.dayOfMonth >= effectiveDay && rule.lastGeneratedMonth != currentMonth
            }.map { it.requiredId }
    }

    /**
     * 규칙 하나의 이번 달 반복 내역을 생성한다. 규칙별 트랜잭션이라 한 규칙의 실패가
     * 다른 규칙 처리에 영향을 주지 않고, lastGeneratedMonth 검사로 같은 달 중복 생성을 막는다.
     */
    @Transactional
    fun generateForRule(
        ruleId: Long,
        today: LocalDate,
    ) {
        val currentMonth = today.format(MONTH_FORMAT)
        val rule = repository.findByIdOrNull(ruleId) ?: return
        val effectiveDay = minOf(rule.dayOfMonth, today.lengthOfMonth())
        if (!rule.active || today.dayOfMonth < effectiveDay || rule.lastGeneratedMonth == currentMonth) return

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
        log.info { "반복 내역 생성: ruleId=$ruleId ($currentMonth)" }
    }

    /**
     * 같은 규칙이 2개면 매달 같은 내역이 2건씩 자동 생성되므로 활성 규칙 간 중복을 막는다.
     * 날짜가 다르면 별개 규칙으로 허용 (매월 1일·15일 같은 금액도 가능하니). 수정 시엔 자기 자신을 제외한다.
     */
    private fun rejectDuplicate(
        user: User,
        dayOfMonth: Int,
        amount: BigDecimal,
        currency: String,
        type: EntryType,
        merchant: String?,
        excludeId: Long? = null,
    ) {
        val duplicate =
            repository.findAllByUser(user).any { rule ->
                rule.requiredId != excludeId &&
                    rule.active &&
                    rule.dayOfMonth == dayOfMonth &&
                    rule.type == type &&
                    rule.currency.equals(currency, ignoreCase = true) &&
                    rule.amount.compareTo(amount) == 0 &&
                    rule.merchant == merchant
            }
        if (duplicate) {
            throw CustomException(ErrorCode.DUPLICATE_RESOURCE, "매월 ${dayOfMonth}일 · ${merchant ?: "반복 내역"}")
        }
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
