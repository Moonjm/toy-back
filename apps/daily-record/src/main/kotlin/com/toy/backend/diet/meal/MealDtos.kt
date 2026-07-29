package com.toy.backend.diet.meal

import com.toy.backend.diet.AnalysisStatus
import com.toy.backend.diet.NutritionSource
import com.toy.backend.diet.score.DietScoreCalculator
import com.toy.backend.diet.score.MealScoreBasis
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.time.LocalDate

/**
 * 확정 요청에 `fileIds`를 다시 보내지 않는다 — `analysisId`로 서버가 사진 목록을 안다.
 * 클라이언트가 파일 목록을 재구성하다 인식에 쓴 사진과 어긋나는 경로를 없앤다.
 *
 * **`analysisId`가 없으면 사진 없이 기록한다.** 검색·직접 입력으로 만든 항목만 저장되고
 * 사진 첨부를 건너뛴다 — 과자 하나를 적으려고 사진을 찍게 만들면 기록을 안 하게 된다.
 */
data class MealConfirmRequest(
    val date: LocalDate,
    val mealType: MealType,
    val analysisId: Long? = null,
    @field:NotEmpty @field:Valid
    val items: List<MealItemRequest>,
)

data class MealItemsRequest(
    @field:NotEmpty @field:Valid
    val items: List<MealItemRequest>,
)

/** 서버는 인식 결과와 대조하지 않고 그대로 신뢰한다 — 확인 단계의 존재 이유가 사용자 판단을 최종으로 삼는 것이다. */
data class MealItemRequest(
    @field:NotBlank @field:Size(max = 200)
    val foodName: String,
    @field:Size(max = 30)
    val foodCode: String? = null,
    @field:PositiveOrZero val quantityG: Double,
    @field:PositiveOrZero val kcal: Double,
    @field:PositiveOrZero val carbsG: Double,
    @field:PositiveOrZero val proteinG: Double,
    @field:PositiveOrZero val fatG: Double,
    @field:PositiveOrZero val sugarG: Double = 0.0,
    @field:PositiveOrZero val sodiumMg: Double = 0.0,
    @field:PositiveOrZero val fiberG: Double = 0.0,
    val source: NutritionSource = NutritionSource.LLM_ESTIMATED,
)

data class MealItemResponse(
    val id: Long,
    val foodName: String,
    val foodCode: String?,
    val quantityG: Double,
    val kcal: Double,
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double,
    val sugarG: Double,
    val sodiumMg: Double,
    val fiberG: Double,
    val source: NutritionSource,
)

data class MealPhotoResponse(
    val fileId: Long,
    val url: String?,
    val sortOrder: Int,
)

data class MealResponse(
    val id: Long,
    val date: LocalDate,
    val mealType: MealType,
    val status: AnalysisStatus,
    val score: Int?,
    val scoreBasis: MealScoreBasis?,
    val totalKcal: Double,
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double,
    val sugarG: Double,
    val sodiumMg: Double,
    val fiberG: Double,
    val feedback: String?,
    val weightKg: Double,
    val targetKcal: Int,
    val photos: List<MealPhotoResponse>,
    val items: List<MealItemResponse>,
)

fun MealItemRequest.toEntity(meal: Meal): MealItem =
    MealItem(
        meal = meal,
        foodName = foodName,
        // 빈 문자열은 「코드 없음」으로 저장한다. @Size(max=30)이 ""를 막지 않으므로 그대로 두면
        // 자주 먹는 음식 집계가 빈 코드끼리 한 덩어리로 묶어 무관한 음식들을 합쳐 버린다.
        foodCode = foodCode?.takeIf { it.isNotBlank() },
        quantityG = quantityG,
        kcal = kcal,
        carbsG = carbsG,
        proteinG = proteinG,
        fatG = fatG,
        sugarG = sugarG,
        sodiumMg = sodiumMg,
        fiberG = fiberG,
        source = source,
    )

/**
 * 점수와 근거를 저장된 매크로에서 **한 번에 함께** 다시 계산한다 — 같은 입력에서 같은 값이 나오는
 * 순수 함수라 중복 저장할 이유가 없다. 저장된 `Meal.score` 컬럼(프롬프트·집계용)을 따로 읽으면
 * 감점 기울기를 튜닝했을 때 `score`와 `scoreBasis`가 서로 어긋난다.
 */
fun Meal.toResponse(urls: Map<Long, String>): MealResponse {
    val scored = DietScoreCalculator.scoreMeal(carbsG, proteinG, fatG)
    return MealResponse(
        id = requiredId,
        date = date,
        mealType = mealType,
        status = status,
        score = scored.score,
        scoreBasis = scored.basis,
        totalKcal = totalKcal,
        carbsG = carbsG,
        proteinG = proteinG,
        fatG = fatG,
        sugarG = sugarG,
        sodiumMg = sodiumMg,
        fiberG = fiberG,
        feedback = feedback,
        weightKg = weightKg,
        targetKcal = targetKcal,
        photos = photos.map { MealPhotoResponse(fileId = it.fileId, url = urls[it.fileId], sortOrder = it.sortOrder) },
        items =
            items.map {
                MealItemResponse(
                    id = it.requiredId,
                    foodName = it.foodName,
                    foodCode = it.foodCode,
                    quantityG = it.quantityG,
                    kcal = it.kcal,
                    carbsG = it.carbsG,
                    proteinG = it.proteinG,
                    fatG = it.fatG,
                    sugarG = it.sugarG,
                    sodiumMg = it.sodiumMg,
                    fiberG = it.fiberG,
                    source = it.source,
                )
            },
    )
}
