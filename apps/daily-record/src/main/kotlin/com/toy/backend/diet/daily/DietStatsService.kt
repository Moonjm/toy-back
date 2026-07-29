package com.toy.backend.diet.daily

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.feedback.NutritionTotals
import com.toy.backend.diet.feedback.totals
import com.toy.backend.diet.meal.FrequentItemResponse
import com.toy.backend.diet.meal.FrequentItemService
import com.toy.backend.diet.meal.Meal
import com.toy.backend.diet.meal.MealRepository
import com.toy.backend.diet.profile.NutritionTargets
import com.toy.backend.diet.score.DietScoreCalculator
import com.toy.backend.user.User
import com.toy.backend.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import kotlin.math.roundToInt

data class DailyScore(
    val date: LocalDate,
    val dayScore: Int,
)

data class DietStatsResponse(
    val from: LocalDate,
    val to: LocalDate,
    /** 기록이 하나라도 있는 날의 수. 평균의 분모다 */
    val recordedDays: Int,
    val averageDayScore: Int?,
    val dailyScores: List<DailyScore>,
    val averageIntake: NutritionTotals?,
    val averageTargets: NutritionTargets?,
    val topFoods: List<FrequentItemResponse>,
)

/**
 * 기간 통계. **전부 `Meal` 합산이라 캐시도 무효화도 없다** — 언제 계산해도 같은 값이 나온다.
 * LLM 조언을 붙이지 않는 이유이기도 하다(붙이면 하루 피드백과 같은 무효화 문제가 생긴다).
 */
@Service
@Transactional(readOnly = true)
class DietStatsService(
    private val mealRepository: MealRepository,
    private val frequentItemService: FrequentItemService,
    private val userRepository: UserRepository,
) {
    fun stats(
        username: String,
        from: LocalDate,
        to: LocalDate,
    ): DietStatsResponse {
        if (from.isAfter(to)) throw CustomException(ErrorCode.INVALID_REQUEST, "from이 to보다 이후일 수 없습니다")
        if (from.plusDays(MAX_RANGE_DAYS) < to) throw CustomException(ErrorCode.INVALID_REQUEST, "기간은 최대 ${MAX_RANGE_DAYS}일입니다")

        val user = findUser(username)
        // createdAt 순으로 받으므로 날짜별로 묶었을 때 각 그룹의 first()가 「그날 첫 끼니」다 —
        // 하루 집계(findByUserAndDateOrderByCreatedAtAscIdAsc)와 같은 정의라 두 화면이 어긋나지 않는다.
        val byDate = mealRepository.findByUserAndDateBetweenOrderByDateAscCreatedAtAscIdAsc(user, from, to).groupBy { it.date }
        val topFoods = frequentItemService.aggregate(user, from, to)

        if (byDate.isEmpty()) {
            return DietStatsResponse(from, to, 0, null, emptyList(), null, null, topFoods)
        }

        val days = byDate.toSortedMap().map { (date, meals) -> date to meals }
        val dailyScores = days.map { (date, meals) -> DailyScore(date, dayScoreOf(meals)) }
        val dailyTotals = days.map { (_, meals) -> meals.totals() }
        // 하루 목표는 그날 첫 끼니의 스냅샷이다 — 하루 집계와 같은 규칙이라 값이 어긋나지 않는다.
        val dailyTargets = days.map { (_, meals) -> meals.first().targets() }

        return DietStatsResponse(
            from = from,
            to = to,
            recordedDays = days.size,
            averageDayScore = dailyScores.map { it.dayScore }.average().roundToInt(),
            dailyScores = dailyScores,
            averageIntake = dailyTotals.average(),
            averageTargets = dailyTargets.average(),
            topFoods = topFoods,
        )
    }

    private fun dayScoreOf(meals: List<Meal>): Int {
        val totals = meals.totals()
        return DietScoreCalculator
            .scoreDay(totals.kcal, totals.carbsG, totals.proteinG, totals.fatG, meals.first().targets())
            .score
    }

    private fun List<NutritionTotals>.average(): NutritionTotals =
        NutritionTotals(
            kcal = map { it.kcal }.average(),
            carbsG = map { it.carbsG }.average(),
            proteinG = map { it.proteinG }.average(),
            fatG = map { it.fatG }.average(),
            sugarG = map { it.sugarG }.average(),
            sodiumMg = map { it.sodiumMg }.average(),
            fiberG = map { it.fiberG }.average(),
        )

    /** 몸무게가 바뀌면 목표도 바뀌므로 기간 평균이 하나로 고정되지 않는다. */
    private fun List<NutritionTargets>.average(): NutritionTargets =
        NutritionTargets(
            kcal = map { it.kcal }.average().roundToInt(),
            carbsG = map { it.carbsG }.average().roundToInt(),
            proteinG = map { it.proteinG }.average().roundToInt(),
            fatG = map { it.fatG }.average().roundToInt(),
            sugarG = map { it.sugarG }.average().roundToInt(),
            sodiumMg = map { it.sodiumMg }.average().roundToInt(),
            fiberG = map { it.fiberG }.average().roundToInt(),
        )

    private fun findUser(username: String): User =
        userRepository.findByUsername(username)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)

    companion object {
        private const val MAX_RANGE_DAYS = 366L
    }
}
