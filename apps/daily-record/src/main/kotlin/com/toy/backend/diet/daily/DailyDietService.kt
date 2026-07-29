package com.toy.backend.diet.daily

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.feedback.DailyDietFeedback
import com.toy.backend.diet.feedback.DailyDietFeedbackRepository
import com.toy.backend.diet.feedback.DietFeedbackGenerator
import com.toy.backend.diet.feedback.NutritionTotals
import com.toy.backend.diet.feedback.totals
import com.toy.backend.diet.meal.Meal
import com.toy.backend.diet.meal.MealRepository
import com.toy.backend.diet.meal.toResponse
import com.toy.backend.diet.score.DietScoreCalculator
import com.toy.backend.file.FileService
import com.toy.backend.user.User
import com.toy.backend.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime

@Service
@Transactional(readOnly = true)
class DailyDietService(
    private val mealRepository: MealRepository,
    private val feedbackRepository: DailyDietFeedbackRepository,
    private val activityRepository: DailyActivityRepository,
    private val userRepository: UserRepository,
    private val feedbackGenerator: DietFeedbackGenerator,
    private val fileService: FileService,
) {
    /** 조회지만 피드백을 lazy 생성·갱신하므로 쓰기 트랜잭션이다. 크론을 두지 않기 위한 선택이다. */
    @Transactional
    fun getDay(
        username: String,
        date: LocalDate,
    ): DayResponse {
        val user = findUser(username)
        val meals = mealRepository.findByUserAndDateOrderByCreatedAtAscIdAsc(user, date)
        val activeEnergyKcal = activityRepository.findByUserAndDate(user, date)?.activeEnergyKcal
        val urls = fileService.getPresignedUrls(meals.flatMap { meal -> meal.photos.map { it.fileId } })
        val totals = meals.totals()

        if (meals.isEmpty()) {
            return DayResponse(
                date = date,
                dayScore = null,
                scoreBasis = null,
                feedback = null,
                totalKcal = 0.0,
                carbsG = 0.0,
                proteinG = 0.0,
                fatG = 0.0,
                activeEnergyKcal = activeEnergyKcal,
                meals = emptyList(),
            )
        }

        // 하루 목표는 **그날 첫 끼니의 스냅샷**에서 읽는다. 현재 프로필을 쓰면 오늘 몸무게를
        // 갱신했을 때 지난주 하루 점수가 같이 바뀐다.
        val targets = meals.first().targets()
        val dayScore = DietScoreCalculator.scoreDay(totals.kcal, totals.carbsG, totals.proteinG, totals.fatG, targets)
        val feedback = resolveFeedback(user, date, meals, totals, dayScore.score, activeEnergyKcal)

        return DayResponse(
            date = date,
            dayScore = dayScore.score,
            scoreBasis = dayScore.basis,
            feedback = feedback,
            totalKcal = totals.kcal,
            carbsG = totals.carbsG,
            proteinG = totals.proteinG,
            fatG = totals.fatG,
            activeEnergyKcal = activeEnergyKcal,
            meals = meals.map { it.toResponse(urls) },
        )
    }

    /**
     * 캐시가 없거나 `generatedAt`이 그날 `Meal`의 최종 `updatedAt`보다 이르면 버리고 재생성한다.
     * 당일에는 식사가 계속 추가되므로 이 조건 없이 캐시하면 미완성 데이터로 만든 피드백이 고정된다.
     */
    private fun resolveFeedback(
        user: User,
        date: LocalDate,
        meals: List<Meal>,
        totals: NutritionTotals,
        dayScore: Int,
        activeEnergyKcal: Int?,
    ): String? {
        val cached = feedbackRepository.findByUserAndDate(user, date)
        val latestMealUpdate = meals.maxOf { it.updatedAt }
        if (cached != null && !cached.generatedAt.isBefore(latestMealUpdate)) return cached.feedback

        val generated =
            feedbackGenerator.generateForDay(meals, totals, meals.first().targets(), dayScore, activeEnergyKcal)
        // 실패는 캐시하지 않는다 — 캐시해 버리면 끼니가 바뀌기 전까지 영영 재시도되지 않는다.
        if (generated == null) return null

        val now = LocalDateTime.now()
        if (cached == null) {
            feedbackRepository.save(
                DailyDietFeedback(
                    user = user,
                    date = date,
                    dayScore = dayScore,
                    feedback = generated,
                    generatedAt = now,
                ),
            )
        } else {
            cached.update(dayScore, generated, now)
        }
        return generated
    }

    private fun findUser(username: String): User =
        userRepository.findByUsername(username)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)
}
