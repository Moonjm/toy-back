package com.toy.backend.dailyrecords

import com.linecorp.kotlinjdsl.querymodel.jpql.path.Paths.path
import com.linecorp.kotlinjdsl.querymodel.jpql.predicate.Predicatable
import com.toy.backend.categories.CategoryRepository
import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import com.toy.backend.common.utils.findAllNotNull
import com.toy.backend.pair.PairRepository
import com.toy.backend.user.User
import com.toy.backend.user.UserRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Service
@Transactional(readOnly = true)
class DailyRecordService(
    private val repository: DailyRecordRepository,
    private val categoryRepository: CategoryRepository,
    private val userRepository: UserRepository,
    private val pairRepository: PairRepository,
) {
    fun list(
        username: String,
        date: LocalDate?,
        from: LocalDate?,
        to: LocalDate?,
    ): List<DailyRecordResponse> {
        val user = findUser(username)
        val entries =
            repository.findAllNotNull {
                val record = entity(DailyRecord::class)
                val predicates = mutableListOf<Predicatable>()
                predicates += path(record, DailyRecord::user).eq(user)
                date?.let { predicates += path(record, DailyRecord::date).eq(it) }
                from?.let { predicates += path(record, DailyRecord::date).ge(it) }
                to?.let { predicates += path(record, DailyRecord::date).le(it) }
                select(record)
                    .from(record)
                    .whereAnd(*predicates.toTypedArray())
                    .orderBy(
                        path(record, DailyRecord::date).asc(),
                        path(record, DailyRecord::id).asc(),
                    )
            }
        return entries.map { it.toResponse() }
    }

    @Transactional
    fun create(
        username: String,
        request: DailyRecordRequest,
    ): Long {
        val category =
            categoryRepository.findByIdOrNull(request.categoryId)
                ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, request.categoryId)

        val entity =
            DailyRecord(
                date = request.date,
                user = findUser(username),
                category = category,
                memo = request.memo,
                together = request.together,
            )
        return repository.save(entity).requiredId
    }

    @Transactional
    fun update(
        username: String,
        id: Long,
        request: DailyRecordRequest,
    ) {
        val entity = authorizedRecord(findUser(username), id)

        val category =
            categoryRepository.findByIdOrNull(request.categoryId)
                ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, request.categoryId)

        entity.updateDetails(
            date = request.date,
            category = category,
            memo = request.memo,
            together = request.together,
        )
    }

    @Transactional
    fun delete(
        username: String,
        id: Long,
    ) {
        val entity = authorizedRecord(findUser(username), id)
        repository.delete(entity)
    }

    /**
     * 수정/삭제 권한이 있는 기록을 조회한다.
     * 본인이 만든 기록이거나, together(함께) 기록이면서 작성자가 내 짝인 경우 허용한다.
     * 권한이 없으면 기존과 동일하게 RESOURCE_NOT_FOUND를 던진다.
     */
    private fun authorizedRecord(
        user: User,
        id: Long,
    ): DailyRecord {
        val entity =
            repository.findByIdOrNull(id)
                ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        val authorized =
            entity.user.requiredId == user.requiredId ||
                (entity.together && entity.user.requiredId == findPartner(user)?.requiredId)
        if (!authorized) {
            throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        }
        return entity
    }

    /** 현재 사용자의 CONNECTED 상태 파트너를 반환한다(없으면 null). */
    private fun findPartner(user: User): User? {
        val pair = pairRepository.findConnectedPair(user) ?: return null
        return if (pair.inviter.requiredId == user.requiredId) pair.partner else pair.inviter
    }

    private fun findUser(username: String): User =
        userRepository.findByUsername(username)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)
}
