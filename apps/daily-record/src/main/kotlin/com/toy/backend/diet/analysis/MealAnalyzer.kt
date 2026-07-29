package com.toy.backend.diet.analysis

import com.toy.backend.diet.AnalysisStatus
import com.toy.backend.diet.NutritionSource
import com.toy.backend.diet.food.FoodMatcher
import com.toy.backend.diet.food.FoodPolicy
import com.toy.backend.diet.food.nutritionFor
import com.toy.backend.diet.llm.OpenRouterClient
import com.toy.backend.diet.llm.RecognizedFood
import com.toy.backend.file.FileService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.repository.findByIdOrNull
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.util.Base64

private val log = KotlinLogging.logger {}

/**
 * 사진마다 독립적으로 이미지 LLM을 호출한다. 한 호출에 여러 장을 넣지 않는 이유는 확인 화면에서
 * "이 항목은 몇 번째 사진에서 나왔다"를 보여주기 위해서다 — 사용자가 중복을 판단하려면 출처가 보여야 한다.
 *
 * **여기까지가 인식이다. 점수도 피드백도 만들지 않는다** — 사용자가 항목을 고칠 수 있으므로
 * 확정 전 수치로 계산하면 버려진다.
 */
@Component
class MealAnalyzer(
    private val repository: MealAnalysisRepository,
    private val fileService: FileService,
    private val foodMatcher: FoodMatcher,
    private val objectMapper: ObjectMapper,
    // API 키가 없으면 빈이 등록되지 않는다 — 로컬에서 키 없이 앱을 띄우기 위한 설계다.
    @Autowired(required = false) private val client: OpenRouterClient?,
) {
    val isAvailable: Boolean get() = client != null

    /**
     * `@Async`는 별도 트랜잭션이므로 **엔티티가 아니라 id를 받아 다시 조회한다.**
     * 호출 측에서 넘긴 엔티티를 그대로 쓰면 준영속 상태 문제가 생긴다.
     */
    @Async
    @Transactional
    fun analyze(analysisId: Long) {
        val analysis = repository.findByIdOrNull(analysisId) ?: return log.warn { "분석 대상이 없다: id=$analysisId" }
        val photos = readResult(analysis).photos.map { analyzePhoto(it.fileId) }
        writeResult(analysis, photos)
    }

    /** 실패한 사진만 다시 부른다 — 성공한 사진을 재호출하면 비용이 이중으로 나가고 결과가 흔들린다. */
    @Async
    @Transactional
    fun retryFailed(analysisId: Long) {
        val analysis = repository.findByIdOrNull(analysisId) ?: return log.warn { "재인식 대상이 없다: id=$analysisId" }
        val photos = readResult(analysis).photos.map { if (it.failed) analyzePhoto(it.fileId) else it }
        writeResult(analysis, photos)
    }

    private fun analyzePhoto(fileId: Long): AnalyzedPhoto {
        val openRouter = client ?: return AnalyzedPhoto(fileId = fileId, failed = true)
        return try {
            val content = fileService.download(fileId)
            // 리사이즈는 하지 않는다 — iOS가 업로드 전에 장변 1024px로 줄여 보낸다.
            // 라즈베리파이에서 이미지를 재인코딩하는 건 낭비다.
            val base64 = Base64.getEncoder().encodeToString(content.bytes)
            val recognized =
                openRouter.recognizeFoods(base64, content.contentType)
                    ?: return AnalyzedPhoto(fileId = fileId, failed = true)
            AnalyzedPhoto(fileId = fileId, failed = false, items = recognized.map { toItem(it) })
        } catch (e: Exception) {
            log.error(e) { "사진 인식 실패: fileId=$fileId" }
            AnalyzedPhoto(fileId = fileId, failed = true)
        }
    }

    /** 식품DB에 붙으면 결정적인 값을, 못 붙으면 LLM 추정값을 쓴다. */
    private fun toItem(recognized: RecognizedFood): AnalyzedItem {
        val matched = foodMatcher.match(recognized.name)
        if (matched == null) {
            return AnalyzedItem(
                foodName = recognized.name,
                foodCode = null,
                quantityG = FoodPolicy.DEFAULT_SERVING_SIZE_G * recognized.portion,
                kcal = recognized.estimatedKcal,
                carbsG = recognized.estimatedCarbsG,
                proteinG = recognized.estimatedProteinG,
                fatG = recognized.estimatedFatG,
                source = NutritionSource.LLM_ESTIMATED,
            )
        }
        val amount = matched.nutritionFor(recognized.portion)
        return AnalyzedItem(
            foodName = matched.name,
            foodCode = matched.code,
            quantityG = amount.quantityG,
            kcal = amount.kcal,
            carbsG = amount.carbsG,
            proteinG = amount.proteinG,
            fatG = amount.fatG,
            source = NutritionSource.DB_MATCHED,
        )
    }

    private fun readResult(analysis: MealAnalysis): AnalysisResult = objectMapper.readValue<AnalysisResult>(analysis.resultJson)

    private fun writeResult(
        analysis: MealAnalysis,
        photos: List<AnalyzedPhoto>,
    ) {
        // 전부 실패했을 때만 FAILED다. 사진 한 장 때문에 나머지 인식 결과를 버리면 전부 다시 올려야 한다.
        val status = if (photos.all { it.failed }) AnalysisStatus.FAILED else AnalysisStatus.COMPLETED
        analysis.updateResult(status, objectMapper.writeValueAsString(AnalysisResult(photos)))
    }
}
