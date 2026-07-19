package com.toy.backend.ledger.apikeys

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import com.toy.backend.common.utils.TokenHasher
import com.toy.backend.user.User
import com.toy.backend.user.UserRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.util.Base64

@Service
@Transactional(readOnly = true)
class ApiKeyService(
    private val repository: ApiKeyRepository,
    private val userRepository: UserRepository,
) {
    private val random = SecureRandom()

    fun list(username: String): List<ApiKeyResponse> =
        repository
            .findAllByUser(findUser(username))
            .map { ApiKeyResponse(id = it.requiredId, name = it.name, createdAt = it.createdAt) }

    @Transactional
    fun issue(
        username: String,
        request: ApiKeyCreateRequest,
    ): ApiKeyIssueResponse {
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        val key = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val entity =
            repository.save(
                ApiKey(
                    user = findUser(username),
                    keyHash = TokenHasher.sha256(key),
                    name = request.name,
                ),
            )
        return ApiKeyIssueResponse(id = entity.requiredId, name = entity.name, key = key)
    }

    @Transactional
    fun delete(
        username: String,
        id: Long,
    ) {
        val user = findUser(username)
        val entity =
            repository.findByIdOrNull(id)
                ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        if (entity.user.requiredId != user.requiredId) {
            throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        }
        repository.delete(entity)
    }

    private fun findUser(username: String): User =
        userRepository.findByUsername(username)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)
}
