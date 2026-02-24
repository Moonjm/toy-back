package com.example.backend.dailyrecords

import com.example.backend.categories.CategoryRepository
import com.example.backend.common.constant.ErrorCode
import com.example.backend.common.exception.CustomException
import com.example.backend.common.utils.findAllNotNull
import com.example.backend.user.User
import com.example.backend.user.UserRepository
import com.linecorp.kotlinjdsl.querymodel.jpql.path.Paths.path
import com.linecorp.kotlinjdsl.querymodel.jpql.predicate.Predicatable
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
        val entity =
            repository.findByIdAndUser(id, findUser(username))
                ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)

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
        val entity =
            repository.findByIdAndUser(id, findUser(username))
                ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        repository.delete(entity)
    }

    private fun findUser(username: String): User =
        userRepository.findByUsername(username)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)
}
