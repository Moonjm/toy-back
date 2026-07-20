package com.toy.backend.ledger.entries

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import com.toy.backend.ledger.categories.Category
import com.toy.backend.ledger.categories.CategoryRepository
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
    private val categoryRepository: CategoryRepository,
    private val userRepository: UserRepository,
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
                category = resolveCategory(user, request.categoryId),
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
            description = request.description,
            category = resolveCategory(user, request.categoryId),
        )
    }

    @Transactional
    fun delete(
        username: String,
        id: Long,
    ) {
        val entity = authorizedEntry(findUser(username), id)
        repository.delete(entity)
    }

    private fun resolveCategory(
        user: User,
        categoryId: Long?,
    ): Category? =
        categoryId?.let { id ->
            val category =
                categoryRepository.findByIdOrNull(id)
                    ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
            if (category.user.requiredId != user.requiredId) {
                throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
            }
            category
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
}
