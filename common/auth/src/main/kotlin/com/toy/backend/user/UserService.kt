package com.toy.backend.user

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class UserService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val eventPublisher: ApplicationEventPublisher,
) {
    fun members(): List<UserResponse> = userRepository.findByAuthorityOrderByIdAsc(Authority.USER).map { it.toResponse() }

    fun me(username: String): UserResponse =
        userRepository.findByUsername(username)?.toResponse()
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)

    @Transactional
    fun updateMe(
        username: String,
        request: UserUpdateRequest,
    ) {
        val user =
            userRepository.findByUsername(username)
                ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)
        val gender = request.gender?.let { Gender.valueOf(it) }
        user.updateProfile(
            name = request.name,
            gender = gender,
            birthDate = request.birthDate,
        )
        // 미전송(null)이면 기존 값 유지 — 프로필 저장이 바코드를 지우지 않도록 한다. 빈 문자열은 삭제.
        request.membershipBarcode?.let { user.membershipBarcode = it.trim().ifBlank { null } }
        request.password?.let { newPassword ->
            val currentPassword =
                request.currentPassword
                    ?: throw CustomException(ErrorCode.INVALID_REQUEST, "currentPassword")
            if (!passwordEncoder.matches(currentPassword, user.passwordHash)) {
                throw CustomException(ErrorCode.INVALID_REQUEST, "currentPassword")
            }
            val encoded =
                passwordEncoder.encode(newPassword)
                    ?: throw CustomException(ErrorCode.INVALID_REQUEST, "password")
            user.updatePassword(encoded)
        }

        // 나이·성별로 계산되는 값(영양 목표치 등)이 인적사항을 따라오게 한다. 예전에는 성별을
        // 잘못 넣었다가 고쳐도 목표치가 그대로였고, 그 낡은 값이 끼니 확정 시 `Meal`에 영구
        // 스냅샷돼 되돌릴 수 없었다. **무엇이 듣는지는 여기서 알지 못한다** — 의존 방향이다.
        eventPublisher.publishEvent(UserProfileChangedEvent(user))
    }
}
