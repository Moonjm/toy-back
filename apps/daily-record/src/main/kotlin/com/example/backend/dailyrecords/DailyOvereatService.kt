package com.example.backend.dailyrecords

import com.example.backend.common.constant.ErrorCode
import com.example.backend.common.exception.CustomException
import com.example.backend.pair.PairService
import com.example.backend.user.User
import com.example.backend.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Service
@Transactional(readOnly = true)
class DailyOvereatService(
    private val repository: DailyOvereatRepository,
    private val userRepository: UserRepository,
    private val pairService: PairService,
) {
    fun list(
        username: String,
        from: LocalDate,
        to: LocalDate,
    ): List<DailyOvereatResponse> {
        val user = findUser(username)
        val effectiveUser = resolveOvereatUser(user)
        return repository.findAllByUserAndDateBetween(effectiveUser, from, to).map { it.toResponse() }
    }

    @Transactional
    fun upsert(
        username: String,
        request: DailyOvereatRequest,
    ) {
        val user = findUser(username)
        val effectiveUser = resolveOvereatUser(user)
        val existing = repository.findByDateAndUser(request.date, effectiveUser)
        if (existing != null) {
            if (request.overeatLevel == OvereatLevel.NONE) {
                repository.delete(existing)
            } else {
                existing.overeatLevel = request.overeatLevel
            }
        } else if (request.overeatLevel != OvereatLevel.NONE) {
            repository.save(
                DailyOvereat(
                    date = request.date,
                    user = effectiveUser,
                    overeatLevel = request.overeatLevel,
                ),
            )
        }
    }

    private fun resolveOvereatUser(user: User): User {
        val pair = pairService.findConnectedPair(user)
        return pair?.inviter ?: user
    }

    private fun findUser(username: String): User =
        userRepository.findByUsername(username)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)
}
