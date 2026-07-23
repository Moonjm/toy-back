package com.toy.backend.ledger.entries

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import com.toy.backend.ledger.inbound.FxMemo
import com.toy.backend.ledger.inbound.FxRateClient
import com.toy.backend.user.User
import com.toy.backend.user.UserRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.YearMonth

@Service
@Transactional(readOnly = true)
class LedgerEntryService(
    private val repository: LedgerEntryRepository,
    private val userRepository: UserRepository,
    private val fxRateClient: FxRateClient,
) {
    /** 기본은 연월 조회, 검색어가 있으면 구매처/내용 부분 일치 검색(연월과 조합 가능). 최소 한 조건은 필수. */
    fun list(
        username: String,
        yearMonth: YearMonth?,
        keyword: String?,
    ): List<LedgerEntryResponse> {
        val normalizedKeyword = keyword?.takeIf { it.isNotBlank() }
        if (yearMonth == null && normalizedKeyword == null) {
            throw CustomException(ErrorCode.INVALID_REQUEST, "yearMonth 또는 keyword 중 하나는 필요합니다")
        }
        val user = findUser(username)
        val start = yearMonth?.atDay(1)?.atStartOfDay()
        return repository
            .search(user, start, start?.plusMonths(1), normalizedKeyword)
            .map { it.toResponse() }
    }

    @Transactional
    fun create(
        username: String,
        request: LedgerEntryRequest,
    ): Long {
        val user = findUser(username)
        val entity =
            LedgerEntry(
                user = user,
                entryAt = request.entryAt,
                amount = request.amount,
                currency = request.currency,
                type = request.type,
                merchant = request.merchant,
                description = request.description,
                source = EntrySource.MANUAL,
            )
        return repository.save(entity).requiredId
    }

    @Transactional
    fun update(
        username: String,
        id: Long,
        request: LedgerEntryRequest,
    ) {
        val user = findUser(username)
        val entity = authorizedEntry(user, id)
        entity.updateDetails(
            entryAt = request.entryAt,
            amount = request.amount,
            currency = request.currency,
            type = request.type,
            merchant = request.merchant,
            description = refreshFxMemoIfDateChanged(entity, request),
        )
    }

    /**
     * 외화 내역의 날짜가 바뀌면 저장된 환율 메모가 옛 날짜 기준이 되므로 새 날짜 고시환율로 다시 만든다.
     * 조회 실패 시 낡은 메모만 걷어낸다 — 틀린 환율을 남기지 않는다.
     */
    private fun refreshFxMemoIfDateChanged(
        entity: LedgerEntry,
        request: LedgerEntryRequest,
    ): String? {
        if (request.currency.uppercase() == "KRW") return request.description
        if (entity.entryAt.toLocalDate() == request.entryAt.toLocalDate()) return request.description
        val base = FxMemo.strip(request.description)
        val rate = fxRateClient.rateToKrw(request.currency, request.entryAt.toLocalDate()) ?: return base
        val memo = FxMemo.build(request.currency, request.amount, rate)
        // 환율 메모가 절단으로 깨지면 다음 수정 때 strip이 인식 못 해 낡은 조각이 남는다 — 기본 메모를 먼저 줄인다.
        return FxMemo.compose(base, memo, DESCRIPTION_MAX_LENGTH)
    }

    @Transactional
    fun delete(
        username: String,
        id: Long,
    ) {
        val entity = authorizedEntry(findUser(username), id)
        repository.delete(entity)
    }

    private fun authorizedEntry(
        user: User,
        id: Long,
    ): LedgerEntry {
        val entity =
            repository.findByIdOrNull(id)
                ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        if (entity.user.requiredId != user.requiredId) {
            throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        }
        return entity
    }

    private fun findUser(username: String): User =
        userRepository.findByUsername(username)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)

    companion object {
        /** ledger_entries.description 컬럼 길이 — 환율 메모를 붙여도 넘지 않게 절단한다. */
        private const val DESCRIPTION_MAX_LENGTH = 500
    }
}
