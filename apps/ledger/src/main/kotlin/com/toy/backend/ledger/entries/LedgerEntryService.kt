package com.toy.backend.ledger.entries

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import com.toy.backend.user.User
import com.toy.backend.user.UserRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Service
@Transactional(readOnly = true)
class LedgerEntryService(
    private val repository: LedgerEntryRepository,
    private val userRepository: UserRepository,
) {
    fun list(
        username: String,
        from: LocalDate,
        to: LocalDate,
    ): List<LedgerEntryResponse> {
        val user = findUser(username)
        return repository
            .findAllByUserAndEntryAtGreaterThanEqualAndEntryAtLessThanOrderByEntryAtDesc(
                user,
                from.atStartOfDay(),
                to.plusDays(1).atStartOfDay(),
            ).map { it.toResponse() }
    }

    @Transactional
    fun create(
        username: String,
        request: LedgerEntryRequest,
    ): Long {
        val entity =
            LedgerEntry(
                user = findUser(username),
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
        val entity = authorizedEntry(findUser(username), id)
        entity.updateDetails(
            entryAt = request.entryAt,
            amount = request.amount,
            currency = request.currency,
            type = request.type,
            merchant = request.merchant,
            description = request.description,
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
