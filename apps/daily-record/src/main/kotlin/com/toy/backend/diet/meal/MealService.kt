package com.toy.backend.diet.meal

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.AnalysisStatus
import com.toy.backend.diet.DietErrorCode
import com.toy.backend.diet.analysis.AnalysisResult
import com.toy.backend.diet.analysis.MealAnalysisRepository
import com.toy.backend.diet.analysis.MealAnalysisService
import com.toy.backend.diet.feedback.DailyDietFeedbackRepository
import com.toy.backend.diet.feedback.DietFeedbackGenerator
import com.toy.backend.diet.profile.NutritionProfileService
import com.toy.backend.diet.runAfterCommit
import com.toy.backend.diet.score.DietScoreCalculator
import com.toy.backend.file.FileService
import com.toy.backend.user.User
import com.toy.backend.user.UserRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.LocalDate

@Service
@Transactional(readOnly = true)
class MealService(
    private val repository: MealRepository,
    private val userRepository: UserRepository,
    private val profileService: NutritionProfileService,
    private val analysisService: MealAnalysisService,
    private val analysisRepository: MealAnalysisRepository,
    private val fileService: FileService,
    private val objectMapper: ObjectMapper,
    private val feedbackGenerator: DietFeedbackGenerator,
    private val dailyFeedbackRepository: DailyDietFeedbackRepository,
) {
    /**
     * 확정. **점수는 동기, 피드백은 비동기다**(피드백 연결은 별도 단계에서 붙인다) —
     * 점수는 룰 기반이라 즉시 나오고 사용자가 바로 봐야 하는 값이지만, 피드백은 LLM 텍스트 호출이라
     * 수 초 걸린다. 확정 응답을 붙잡을 이유가 없다.
     *
     * `attachFile`이 실패하면 트랜잭션 전체가 롤백된다. detach 전환 덕에 이미 attach된 사진의
     * S3 객체는 사라지지 않고, 커밋되지 않았으므로 `TEMP`로 남아 정리 배치가 수거한다.
     */
    @Transactional
    fun confirm(
        username: String,
        request: MealConfirmRequest,
    ): Long {
        if (request.items.isEmpty()) throw CustomException(ErrorCode.INVALID_REQUEST, "항목이 비어 있습니다")
        val user = findUser(username)
        val profile = profileService.requireProfile(user)
        val analysis = analysisService.requireOwned(user, request.analysisId)
        if (analysis.status == AnalysisStatus.PENDING) {
            throw CustomException(DietErrorCode.ANALYSIS_NOT_CONFIRMABLE, request.analysisId)
        }

        val meal =
            Meal(
                user = user,
                date = request.date,
                mealType = request.mealType,
                // 확정 시점 스냅샷 — 나중에 몸무게를 갱신해도 이 끼니가 속한 날의 점수는 흔들리지 않는다.
                weightKg = profile.weightKg,
                targetKcal = profile.targetKcal,
                targetCarbsG = profile.targetCarbsG,
                targetProteinG = profile.targetProteinG,
                targetFatG = profile.targetFatG,
            )
        applyItems(meal, request.items)

        val photos = objectMapper.readValue<AnalysisResult>(analysis.resultJson).photos
        photos.forEachIndexed { index, photo ->
            fileService.attachFile(photo.fileId, MEAL_FILE_PREFIX)
            meal.addPhoto(MealPhoto(meal = meal, fileId = photo.fileId, sortOrder = index))
        }

        val saved = repository.save(meal)
        analysisRepository.delete(analysis)
        // 커밋 뒤에 시작해야 비동기 스레드가 저장된 끼니를 볼 수 있다.
        runAfterCommit { feedbackGenerator.generateForMeal(saved.requiredId) }
        return saved.requiredId
    }

    fun get(
        username: String,
        id: Long,
    ): MealResponse {
        val meal = requireOwned(findUser(username), id)
        return meal.toResponse(fileService.getPresignedUrls(meal.photos.map { it.fileId }))
    }

    fun list(
        username: String,
        from: LocalDate,
        to: LocalDate,
    ): List<MealResponse> {
        if (from.isAfter(to)) throw CustomException(ErrorCode.INVALID_REQUEST, "from이 to보다 이후일 수 없습니다")
        val meals = repository.findByUserAndDateBetweenOrderByDateAscIdAsc(findUser(username), from, to)
        // 목록에서 사진마다 presign 하면 N+1이다 — 한 번에 받는다.
        val urls = fileService.getPresignedUrls(meals.flatMap { meal -> meal.photos.map { it.fileId } })
        return meals.map { it.toResponse(urls) }
    }

    /** 항목을 고치면 영양소·점수를 다시 계산하고 피드백도 재생성한다 — 낡은 조언을 남기지 않는다. */
    @Transactional
    fun updateItems(
        username: String,
        id: Long,
        request: MealItemsRequest,
    ) {
        if (request.items.isEmpty()) throw CustomException(ErrorCode.INVALID_REQUEST, "항목이 비어 있습니다")
        val meal = requireOwned(findUser(username), id)
        applyItems(meal, request.items)
        meal.markFeedbackPending()
        runAfterCommit { feedbackGenerator.generateForMeal(id) }
    }

    /**
     * 삭제. **파일을 물리 삭제하지 않고 detach 한다** — 상태를 `TEMP`로 되돌리기만 하고 S3 객체는
     * 매일 04:00 정리 배치가 수거한다. 트랜잭션이 롤백되면 상태 변경도 함께 되돌아가므로
     * "레코드는 살아났는데 객체는 사라진" 상태가 생기지 않는다.
     *
     * 그날의 하루 피드백 캐시도 함께 지운다 — 남은 끼니의 `updatedAt`은 바뀌지 않아 캐시 무효화
     * 조건만으로는 잡히지 않고, 그대로 두면 지운 끼니를 언급하는 문장이 남는다.
     */
    @Transactional
    fun delete(
        username: String,
        id: Long,
    ) {
        val meal = requireOwned(findUser(username), id)
        fileService.detachFiles(meal.photos.map { it.fileId })
        repository.delete(meal)
        dailyFeedbackRepository.deleteByUserAndDate(meal.user, meal.date)
    }

    /** 자동 재시도를 넣지 않는 대신 수동 재시도를 연다. 실패 상태에서만 허용해 중복 호출을 막는다. */
    @Transactional
    fun retryFeedback(
        username: String,
        id: Long,
    ) {
        val meal = requireOwned(findUser(username), id)
        if (meal.status != AnalysisStatus.FAILED) throw CustomException(DietErrorCode.FEEDBACK_NOT_RETRYABLE, id)
        meal.markFeedbackPending()
        runAfterCommit { feedbackGenerator.generateForMeal(id) }
    }

    /** 항목 교체 → 영양소 합산 → 점수 재계산. 확정과 수정이 이 한 곳을 공유한다. */
    fun applyItems(
        meal: Meal,
        items: List<MealItemRequest>,
    ) {
        meal.replaceItems(items.map { it.toEntity(meal) })
        meal.applyScore(DietScoreCalculator.scoreMeal(meal.carbsG, meal.proteinG, meal.fatG).score)
    }

    fun requireOwned(
        user: User,
        id: Long,
    ): Meal {
        val meal =
            repository.findByIdOrNull(id)
                ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        if (meal.user.requiredId != user.requiredId) {
            throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        }
        return meal
    }

    fun findUser(username: String): User =
        userRepository.findByUsername(username)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)

    companion object {
        private const val MEAL_FILE_PREFIX = "meals/"
    }
}
