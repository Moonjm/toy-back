package com.toy.backend.diet.meal

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.NutritionSource
import com.toy.backend.diet.food.FoodNameNormalizer
import com.toy.backend.user.User
import com.toy.backend.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * 응답 한 건이 그대로 `MealItemRequest`가 되도록 필드를 맞춘다 — 앱이 탭 한 번으로 담고
 * 영양소를 다시 계산하지 않는다.
 */
data class FrequentItemResponse(
    val foodName: String,
    val foodCode: String?,
    val quantityG: Double,
    val kcal: Double,
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double,
    val source: NutritionSource,
    val count: Int,
    val lastEatenOn: LocalDate,
)

@Service
@Transactional(readOnly = true)
class FrequentItemService(
    private val repository: MealItemRepository,
    private val userRepository: UserRepository,
) {
    fun list(
        username: String,
        days: Int,
        size: Int,
    ): List<FrequentItemResponse> {
        val to = LocalDate.now()
        val from = to.minusDays(days.coerceIn(1, MAX_DAYS).toLong())
        return aggregate(findUser(username), from, to).take(size.coerceIn(1, MAX_SIZE))
    }

    /**
     * 기간 내 항목을 빈도순(동률이면 최근순)으로 묶는다. Task 6의 기간 통계가 그대로 재사용한다.
     *
     * **메모리에서 묶는다.** `group by`로 빈도를 구한 뒤 대표 항목을 다시 찾으면 N+1이 되는데,
     * 사용자 2명·30일이면 많아야 수백 건이라 그 복잡도를 낼 이유가 없다.
     */
    fun aggregate(
        user: User,
        from: LocalDate,
        to: LocalDate,
    ): List<FrequentItemResponse> =
        repository
            .findEatenBetween(user, from, to)
            // foodCode가 없는 직접 입력 항목은 정규화한 이름으로 묶어 띄어쓰기 차이를 흡수한다.
            .groupBy { it.foodCode ?: FoodNameNormalizer.normalize(it.foodName) }
            .map { (_, group) -> group.toResponse() }
            .sortedWith(compareByDescending<FrequentItemResponse> { it.count }.thenByDescending { it.lastEatenOn })

    /** 리포지토리가 최근순으로 주므로 첫 건이 가장 최근에 먹은 것이다. */
    private fun List<MealItem>.toResponse(): FrequentItemResponse {
        val latest = first()
        return FrequentItemResponse(
            foodName = latest.foodName,
            foodCode = latest.foodCode,
            quantityG = latest.quantityG,
            kcal = latest.kcal,
            carbsG = latest.carbsG,
            proteinG = latest.proteinG,
            fatG = latest.fatG,
            source = latest.source,
            count = size,
            lastEatenOn = latest.meal.date,
        )
    }

    private fun findUser(username: String): User =
        userRepository.findByUsername(username)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)

    companion object {
        private const val MAX_DAYS = 90
        private const val MAX_SIZE = 50
    }
}
