package com.toy.backend.diet.daily

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.feedback.DailyDietFeedbackRepository
import com.toy.backend.user.User
import com.toy.backend.user.UserRepository
import jakarta.validation.constraints.PositiveOrZero
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

data class ActivityUpsertRequest(
    val date: LocalDate,
    @field:PositiveOrZero val activeEnergyKcal: Int,
)

@Service
@Transactional(readOnly = true)
class DailyActivityService(
    private val repository: DailyActivityRepository,
    private val userRepository: UserRepository,
    private val dailyFeedbackRepository: DailyDietFeedbackRepository,
) {
    /**
     * 활동 에너지는 하루 마감 피드백 프롬프트에 들어간다. 갱신했는데 캐시를 그대로 두면
     * `Meal.updatedAt` 기반 무효화 조건이 이 변경을 못 잡아 낡은 활동 에너지로 만든 문장이 남는다.
     */
    @Transactional
    fun upsert(
        username: String,
        request: ActivityUpsertRequest,
    ) {
        val user = findUser(username)
        dailyFeedbackRepository.deleteByUserAndDate(user, request.date)
        val existing = repository.findByUserAndDate(user, request.date)
        if (existing != null) {
            existing.updateEnergy(request.activeEnergyKcal)
            return
        }
        repository.save(
            DailyActivity(user = user, date = request.date, activeEnergyKcal = request.activeEnergyKcal),
        )
    }

    private fun findUser(username: String): User =
        userRepository.findByUsername(username)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)
}
