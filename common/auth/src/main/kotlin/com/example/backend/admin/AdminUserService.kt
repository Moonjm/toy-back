package com.example.backend.admin

import com.example.backend.auth.security.RefreshTokenRepository
import com.example.backend.common.constant.ErrorCode
import com.example.backend.common.exception.CustomException
import com.example.backend.user.User
import com.example.backend.user.UserRepository
import com.example.backend.user.UserResponse
import com.example.backend.user.toResponse
import org.springframework.data.repository.findByIdOrNull
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class AdminUserService(
    private val userRepository: UserRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val passwordEncoder: PasswordEncoder,
) {
    fun list(excludeUsername: String): List<UserResponse> {
        val excludeUserId = findUserByUsername(excludeUsername).requiredId
        return userRepository
            .findAll()
            .filterNot { it.requiredId == excludeUserId }
            .sortedBy { it.requiredId }
            .map { it.toResponse() }
    }

    fun get(id: Long): UserResponse = findUser(id).toResponse()

    @Transactional
    fun update(
        id: Long,
        request: AdminUserUpdateRequest,
    ) {
        val user = findUser(id)

        val encoded =
            passwordEncoder.encode(request.password)
                ?: throw CustomException(ErrorCode.INVALID_REQUEST, "password")
        user.updateCredentials(encoded, request.authority)
        user.updateProfile(request.name)
    }

    @Transactional
    fun unlock(id: Long) {
        val user = findUser(id)
        user.unlock()
    }

    @Transactional
    fun delete(id: Long) {
        val user = findUser(id)
        refreshTokenRepository.deleteAllByUserId(id)
        userRepository.delete(user)
    }

    private fun findUser(id: Long): User =
        userRepository.findByIdOrNull(id)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)

    private fun findUserByUsername(username: String): User =
        userRepository.findByUsername(username)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)
}
