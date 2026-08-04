package com.toy.backend.diet.meal

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.AnalysisStatus
import com.toy.backend.diet.DietErrorCode
import com.toy.backend.diet.analysis.AnalysisResult
import com.toy.backend.diet.analysis.MealAnalysis
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
     * 확정. **점수는 동기, 피드백은 비동기다** — 점수는 룰 기반이라 즉시 나오고 사용자가 바로
     * 봐야 하는 값이지만, 피드백은 LLM 텍스트 호출이라 수 초 걸린다.
     *
     * **같은 날 같은 끼니가 이미 있으면 새로 만들지 않고 거기 합친다**(간식 제외 —
     * `MealType.mergesWithinDay`). 아침을 먹다가 하나 더 먹어서 추가하면 아침 카드가 두 개
     * 생기던 것을 막는다. 합칠 때도 응답은 그대로 201 + `Location`이고 id는 합쳐진 기존
     * 끼니의 것이다 — 생성하지 않았는데 201이라 HTTP 의미와는 어긋나지만, 이 API의 유일한
     * 소비자인 앱이 201만 받도록 돼 있어 시맨틱을 맞추자고 클라이언트를 깨뜨리지 않는다.
     *
     * `analysisId`가 없으면 **사진 없는 기록**이다. 분석 조회·`attachFile`·분석 삭제를 통째로
     * 건너뛴다. 프로필은 사진 유무와 무관하게 필요하다 — 목표 스냅샷을 떠야 하기 때문이다.
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
        // 분석은 끼니를 만들기 전에 검증한다 — 확정할 수 없는 분석이면 아무것도 만들지 않고 끝낸다.
        val analysis = request.analysisId?.let { confirmableAnalysis(user, it) }

        val existing = mergeTargetOf(user, request.date, request.mealType)
        val meal =
            existing
                ?: Meal(
                    user = user,
                    date = request.date,
                    mealType = request.mealType,
                    // 확정 시점 스냅샷 — 나중에 몸무게를 갱신해도 이 끼니가 속한 날의 점수는 흔들리지 않는다.
                    // **병합할 때는 갱신하지 않는다.** 그 끼니를 처음 확정한 시점의 값이 그 끼니를
                    // 설명하는 값이고, 아침 점수의 근거가 저녁에 잰 몸무게로 바뀌면 「과거 점수는
                    // 바뀌지 않는다」는 약속이 깨진다.
                    weightKg = profile.weightKg,
                    targetKcal = profile.targetKcal,
                    targetCarbsG = profile.targetCarbsG,
                    targetProteinG = profile.targetProteinG,
                    targetFatG = profile.targetFatG,
                    targetSugarG = profile.targetSugarG,
                    targetSodiumMg = profile.targetSodiumMg,
                    targetFiberG = profile.targetFiberG,
                )

        // 새 끼니는 `items`가 비어 있어 얹기와 교체가 같다 — 분기하지 않는다.
        meal.addItems(request.items.map { it.toEntity(meal) })
        meal.applyScore(DietScoreCalculator.scoreMeal(meal.carbsG, meal.proteinG, meal.fatG).score)
        // 항목이 늘었으므로 옛 피드백은 버린다(`updateItems`와 같은 처리). 새 끼니에는 이미
        // 그 상태라 무해하다. 하루 피드백 캐시는 따로 지우지 않아도 된다 — 무효화 판정이
        // `Meal.updatedAt`을 보는데 병합이 그 값을 올린다.
        meal.markFeedbackPending()
        analysis?.let { attachPhotos(meal, it) }

        // 기존 끼니는 영속 상태라 더티 체킹으로 반영된다. 새 끼니만 저장한다.
        val saved = existing ?: repository.save(meal)
        analysis?.let { analysisRepository.delete(it) }
        // 커밋 뒤에 시작해야 비동기 스레드가 저장된 끼니를 볼 수 있다.
        runAfterCommit { feedbackGenerator.generateForMeal(saved.requiredId) }
        return saved.requiredId
    }

    /** 합칠 기존 끼니. 간식은 본래 여러 번이라 묶지 않는다(`MealType.mergesWithinDay`). */
    private fun mergeTargetOf(
        user: User,
        date: LocalDate,
        mealType: MealType,
    ): Meal? =
        if (mealType.mergesWithinDay) {
            repository.findFirstByUserAndDateAndMealTypeOrderByCreatedAtAscIdAsc(user, date, mealType)
        } else {
            null
        }

    private fun confirmableAnalysis(
        user: User,
        analysisId: Long,
    ): MealAnalysis {
        val analysis = analysisService.requireOwned(user, analysisId)
        if (analysis.status == AnalysisStatus.PENDING) {
            throw CustomException(DietErrorCode.ANALYSIS_NOT_CONFIRMABLE, analysisId)
        }
        return analysis
    }

    /**
     * 인식이 실패한 사진도 붙인다 — 인식이 안 됐을 뿐 사용자가 찍은 그 끼니의 사진이다.
     *
     * **`sortOrder`는 기존 최대값 다음부터 매긴다.** `Meal.photos`가 `@OrderBy("sortOrder asc")`라
     * 병합할 때 0부터 다시 매기면 `0,1,0,1`이 되어 앱의 사진 순서가 뒤섞인다. 새 끼니는
     * `photos`가 비어 있어 0부터 시작하므로 분기하지 않는다.
     */
    private fun attachPhotos(
        meal: Meal,
        analysis: MealAnalysis,
    ) {
        val startOrder = (meal.photos.maxOfOrNull { it.sortOrder } ?: -1) + 1
        objectMapper.readValue<AnalysisResult>(analysis.resultJson).photos.forEachIndexed { index, photo ->
            fileService.attachFile(photo.fileId, MEAL_FILE_PREFIX)
            meal.addPhoto(MealPhoto(meal = meal, fileId = photo.fileId, sortOrder = startOrder + index))
        }
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
     * 저장된 끼니의 **종류만** 고친다. 저녁을 간식으로 저장하면 되돌릴 길이 없던 것을 연다 —
     * 앱에 사진 바이트가 없어 「지우고 다시 만들기」로는 찍어 둔 사진이 사라진다.
     *
     * 세 갈래다. ① 같은 종류면 아무것도 하지 않는다 — 앱이 실수로 같은 값을 보내도 유료 호출이
     * 나가면 안 된다. ② 대상 종류의 끼니가 그날 없으면 종류만 바꾼다. ③ 있으면 그쪽으로 합친다.
     *
     * **대상을 찾기 전에 종류를 바꾸면 안 된다.** Hibernate가 쿼리 전에 auto-flush 하므로
     * 병합 대상 조회가 **방금 바꾼 자기 자신**을 돌려줄 수 있다 — 자기 항목을 자기에게 붙이고
     * 자기를 지우게 된다.
     *
     * 점수는 다시 계산하지 않는다 — `DietScoreCalculator`는 종류를 쓰지 않는다. 피드백은 다시
     * 만든다 — `DietFeedbackPrompts.meal`이 `[이번 끼니] ${meal.mealType}`을 읽는다.
     *
     * 돌려주는 것은 **살아남은 끼니의 id**다. 합쳤으면 대상, 아니면 요청한 id 그대로다.
     */
    @Transactional
    fun changeType(
        username: String,
        id: Long,
        request: MealTypeRequest,
    ): Long {
        val user = findUser(username)
        val meal = requireOwned(user, id)
        if (meal.mealType == request.mealType) return id

        meal.changeMealType(request.mealType)
        meal.markFeedbackPending()
        runAfterCommit { feedbackGenerator.generateForMeal(id) }
        return id
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

    /**
     * 사진 한 장 삭제. 잘못 찍은 사진 하나 때문에 끼니를 통째로 지우고 항목을 다시 넣게 만들지 않는다.
     *
     * **항목·점수·피드백은 건드리지 않는다.** 점수는 항목에서만 나오고(`scoreMeal(탄, 단, 지)`)
     * 피드백 프롬프트에도 사진이 안 들어간다 — 사진이 줄어도 먹은 것은 그대로다. `updateItems`를
     * 흉내 내 재생성을 걸면 같은 내용의 문장을 다시 만들면서 LLM 비용만 나간다. 하루 피드백 캐시도
     * 건드리지 않는다(구성이 안 바뀌었다).
     *
     * 파일은 물리 삭제하지 않고 detach 한다 — `delete`와 같은 이유다.
     *
     * **`sortOrder`는 다시 매기지 않는다.** 0,1,2에서 1을 빼면 0,2가 남는데 `@OrderBy("sortOrder asc")`라
     * 순서는 그대로 맞다. 오히려 구멍을 남기는 편이 낫다 — 끼니 병합이 사진을 이어 붙일 때
     * `maxOfOrNull { it.sortOrder } + 1`을 쓰므로, 다시 매기면 그쪽과 값이 겹칠 여지가 생긴다.
     */
    @Transactional
    fun deletePhoto(
        username: String,
        mealId: Long,
        fileId: Long,
    ) {
        val meal = requireOwned(findUser(username), mealId)
        // 그 끼니에 없는 사진이면 404 — 남의 사진 id를 넣어도 지워지지 않는다.
        if (!meal.removePhoto(fileId)) throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, fileId)
        fileService.detachFiles(listOf(fileId))
    }

    /** 자동 재시도를 넣지 않는 대신 수동 재시도를 연다. 실패 상태에서만 허용해 중복 호출을 막는다. */
    @Transactional
    fun retryFeedback(
        username: String,
        id: Long,
    ) {
        // `MealAnalysisService.retry`와 같은 가드다. 키가 없으면 생성기가 publish(null)로 다시
        // FAILED를 만드는 것밖에 못 하는데, 여기서 204를 돌려주면 「접수됐다」는 거짓말이 된다.
        if (!feedbackGenerator.isAvailable) throw CustomException(DietErrorCode.LLM_UNAVAILABLE)

        val meal = requireOwned(findUser(username), id)
        if (meal.status != AnalysisStatus.FAILED) throw CustomException(DietErrorCode.FEEDBACK_NOT_RETRYABLE, id)
        meal.markFeedbackPending()
        runAfterCommit { feedbackGenerator.generateForMeal(id) }
    }

    /**
     * 항목 **교체** → 영양소 합산 → 점수 재계산. 수정 전용이다.
     *
     * 확정은 이 경로를 쓰지 않는다 — 같은 끼니에 다시 기록하면 합쳐야 하는데 교체는 기존
     * 항목을 지운다(`Meal.addItems` 주석 참조).
     */
    private fun applyItems(
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
