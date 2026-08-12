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

    /**
     * 인적사항이 바뀌었을 때 저장된 목표치를 다시 계산한다. 목표치는 `gender`·`birthDate`로
     * 계산되는데 예전에는 `save`·`updateWeight`에서만 다시 계산돼, 성별을 잘못 넣었다가
     * 고쳐도 목표치가 그대로였다. 그 낡은 값이 끼니 확정 시 `Meal`에 **영구 스냅샷**되어
     * 나중에 고쳐도 되돌아가지 않았다.
     *
     * **아무것도 못 해도 조용히 넘어간다.** 인적사항 수정은 영양 프로필과 독립적인 기능이라
     * 여기서 던지면 프로필이 없는 사용자의 이름 변경까지 실패한다. 특히 `User.updateProfile`은
     * `gender`·`birthDate`를 **받은 값으로 그대로 덮으므로**(null이면 지운다) 이름만 보내는
     * 요청이 성별을 비울 수 있다 — 그때는 계산식을 세울 수 없으니 기존 목표치를 남겨 둔다.
     */
    @Transactional
    fun recalculateTargets(user: User) {
        val profile = repository.findByUser(user) ?: return
        if (user.gender == null || user.birthDate == null) return
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
