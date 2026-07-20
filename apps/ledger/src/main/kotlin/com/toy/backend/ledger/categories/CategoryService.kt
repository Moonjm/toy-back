package com.toy.backend.ledger.categories

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import com.toy.backend.user.User
import com.toy.backend.user.UserRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class CategoryService(
    private val repository: CategoryRepository,
    private val userRepository: UserRepository,
) {
    fun list(username: String): List<CategoryResponse> = repository.findAllByUserOrderByNameAsc(findUser(username)).map { it.toResponse() }

    @Transactional
    fun create(
        username: String,
        request: CategoryRequest,
    ): Long {
        val user = findUser(username)
        if (repository.findByUserAndName(user, request.name) != null) {
            throw CustomException(ErrorCode.DUPLICATE_RESOURCE, request.name)
        }
        return repository.save(Category(user = user, name = request.name)).requiredId
    }

    @Transactional
    fun update(
        username: String,
        id: Long,
        request: CategoryRequest,
    ) {
        val user = findUser(username)
        val category = authorizedCategory(user, id)
        val duplicate = repository.findByUserAndName(user, request.name)
        if (duplicate != null && duplicate.requiredId != category.requiredId) {
            throw CustomException(ErrorCode.DUPLICATE_RESOURCE, request.name)
        }
        category.rename(request.name)
    }

    /** 내역/반복규칙이 참조 중이면 FK 위반으로 409(RESOURCE_STILL_REFERENCED)가 반환된다. */
    @Transactional
    fun delete(
        username: String,
        id: Long,
    ) {
        val category = authorizedCategory(findUser(username), id)
        repository.delete(category)
    }

    private fun authorizedCategory(
        user: User,
        id: Long,
    ): Category {
        val category =
            repository.findByIdOrNull(id)
                ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        if (category.user.requiredId != user.requiredId) {
            throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        }
        return category
    }

    private fun findUser(username: String): User =
        userRepository.findByUsername(username)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)
}
