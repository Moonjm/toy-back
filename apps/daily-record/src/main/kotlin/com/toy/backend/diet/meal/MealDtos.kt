package com.toy.backend.diet.meal

import com.toy.backend.diet.AnalysisStatus
import com.toy.backend.diet.NutritionSource
import com.toy.backend.diet.score.DietScoreCalculator
import com.toy.backend.diet.score.MealScoreBasis
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.time.LocalDate

/**
 * 확정 요청에 `fileIds`를 다시 보내지 않는다 — `analysisId`로 서버가 사진 목록을 안다.
 * 클라이언트가 파일 목록을 재구성하다 인식에 쓴 사진과 어긋나는 경로를 없앤다.
 */
data class MealConfirmRequest(
    val date: LocalDate,
    val mealType: MealType,
    val analysisId: Long,
    @field:NotEmpty @field:Valid
    val items: List<MealItemRequest>,
)

data class MealItemsRequest(
    @field:NotEmpty @field:Valid
    val items: List<MealItemRequest>,
)

/** 서버는 인식 결과와 대조하지 않고 그대로 신뢰한다 — 확인 단계의 존재 이유가 사용자 판단을 최종으로 삼는 것이다. */
data class MealItemRequest(
    @field:Size(max = 200)
    val foodName: String,
    @field:Size(max = 30)
    val foodCode: String? = null,
    @field:PositiveOrZero val quantityG: Double,
    @field:PositiveOrZero val kcal: Double,
    @field:PositiveOrZero val carbsG: Double,
    @field:PositiveOrZero val proteinG: Double,
    @field:PositiveOrZero val fatG: Double,
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
        foodCode = foodCode,
        quantityG = quantityG,
        kcal = kcal,
        carbsG = carbsG,
        proteinG = proteinG,
        fatG = fatG,
        source = source,
    )

/**
 * 점수 근거는 저장하지 않고 저장된 매크로에서 다시 계산한다 — 같은 입력에서 같은 값이 나오는
 * 순수 함수라 중복 저장할 이유가 없다. 감점 기울기를 튜닝하면 과거 끼니의 근거 표시도 함께 따라온다.
 */
fun Meal.toResponse(urls: Map<Long, String>): MealResponse =
    MealResponse(
        id = requiredId,
        date = date,
        mealType = mealType,
        status = status,
        score = score,
        scoreBasis = DietScoreCalculator.scoreMeal(carbsG, proteinG, fatG).basis,
        totalKcal = totalKcal,
        carbsG = carbsG,
        proteinG = proteinG,
        fatG = fatG,
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
                    source = it.source,
                )
            },
    )
