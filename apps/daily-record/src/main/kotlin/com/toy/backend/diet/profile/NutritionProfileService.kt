package com.toy.backend.diet.profile

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.DietErrorCode
import com.toy.backend.user.User
import com.toy.backend.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Service
@Transactional(readOnly = true)
class NutritionProfileService(
    private val repository: NutritionProfileRepository,
    private val userRepository: UserRepository,
) {
    fun get(username: String): NutritionProfileResponse = requireProfile(findUser(username)).toResponse()

    @Transactional
    fun save(
        username: String,
        request: NutritionProfileRequest,
    ) {
        val user = findUser(username)
        val existing = repository.findByUser(user)
        val profile =
            existing?.apply {
                updateDetails(request.heightCm, request.weightKg, request.activityLevel, request.goal)
            } ?: NutritionProfile(
                user = user,
                heightCm = request.heightCm,
                weightKg = request.weightKg,
                activityLevel = request.activityLevel,
                goal = request.goal,
            )
        profile.applyTargets(calculateTargets(user, profile))
        if (existing == null) repository.save(profile)
    }

    /** 매일 재는 몸무게만 갱신하고 목표를 다시 계산한다. 과거 점수는 `Meal` 스냅샷을 쓰므로 영향받지 않는다. */
    @Transactional
    fun updateWeight(
        username: String,
        request: WeightUpdateRequest,
    ) {
        val user = findUser(username)
        val profile = requireProfile(user)
        profile.updateWeight(request.weightKg)
        profile.applyTargets(calculateTargets(user, profile))
    }

    fun requireProfile(user: User): NutritionProfile =
        repository.findByUser(user)
            ?: throw CustomException(DietErrorCode.PROFILE_NOT_FOUND)

    private fun calculateTargets(
        user: User,
        profile: NutritionProfile,
    ): NutritionTargets {
        val gender = user.gender
        val birthDate = user.birthDate
        // 나이·성별이 없으면 Mifflin-St Jeor 식을 세울 수 없다. 추정으로 목표를 만들면 점수를 설명할 수 없다.
        if (gender == null || birthDate == null) {
            throw CustomException(ErrorCode.INVALID_REQUEST, "생년월일과 성별을 먼저 등록해 주세요")
        }
        return NutritionTargetCalculator.calculate(
            gender = gender,
            birthDate = birthDate,
            heightCm = profile.heightCm,
            weightKg = profile.weightKg,
            activityLevel = profile.activityLevel,
            goal = profile.goal,
            today = LocalDate.now(),
        )
    }

    private fun findUser(username: String): User =
        userRepository.findByUsername(username)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)
}
