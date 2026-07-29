package com.toy.backend.diet.daily

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
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
) {
    @Transactional
    fun upsert(
        username: String,
        request: ActivityUpsertRequest,
    ) {
        val user = findUser(username)
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
