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
    /**
     * 활동 에너지 갱신은 하루 피드백 캐시를 지우지 않는다 — 의도적으로 두 가지를 지킨다.
     * (1) `dayScore`에 활동 에너지가 들어가지 않으므로 캐시 재사용이 점수와 어긋날 일이 없다.
     * (2) 활동 에너지는 하루 종일 계속 증가하는 값이라, iOS가 화면을 열 때마다
     *     HealthKit 누적치로 이 API를 호출한다 — 여기서 캐시를 지우면 화면을 열 때마다
     *     LLM을 다시 부르는 비용 폭증으로 이어진다(이 도메인은 자동 재시도를 넣지 않는 등
     *     비용 경로를 의도적으로 막아 왔다). 피드백 문장이 조금 낡은 활동량을 말할 수 있는
     *     정도는 감수한다 — 끼니가 바뀌면 `updatedAt` 조건으로 어차피 재생성된다.
     */
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
