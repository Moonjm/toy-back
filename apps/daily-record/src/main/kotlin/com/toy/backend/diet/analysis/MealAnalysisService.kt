package com.toy.backend.diet.analysis

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import com.toy.backend.diet.AnalysisStatus
import com.toy.backend.diet.DietErrorCode
import com.toy.backend.diet.runAfterCommit
import com.toy.backend.file.FileService
import com.toy.backend.user.User
import com.toy.backend.user.UserRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue

@Service
@Transactional(readOnly = true)
class MealAnalysisService(
    private val repository: MealAnalysisRepository,
    private val userRepository: UserRepository,
    private val fileService: FileService,
    private val analyzer: MealAnalyzer,
    private val objectMapper: ObjectMapper,
) {
    @Transactional
    fun create(
        username: String,
        request: AnalysisCreateRequest,
    ): Long {
        val fileIds = request.fileIds.distinct()
        if (fileIds.isEmpty()) throw CustomException(ErrorCode.INVALID_REQUEST, "사진을 한 장 이상 올려주세요")
        // 사진마다 이미지 LLM을 호출하므로 장수가 곧 비용·지연이다.
        if (fileIds.size > MAX_PHOTOS) throw CustomException(DietErrorCode.PHOTO_LIMIT_EXCEEDED, MAX_PHOTOS)
        // 인식은 LLM 없이 대체 경로가 없다 — 진행시켜 봐야 FAILED 레코드만 쌓인다.
        if (!analyzer.isAvailable) throw CustomException(DietErrorCode.LLM_UNAVAILABLE)

        val analysis =
            MealAnalysis(
                user = findUser(username),
                status = AnalysisStatus.PENDING,
                resultJson = objectMapper.writeValueAsString(AnalysisResult(fileIds.map { AnalyzedPhoto(fileId = it) })),
            )
        val id = repository.save(analysis).requiredId
        runAfterCommit { analyzer.analyze(id) }
        return id
    }

    fun get(
        username: String,
        id: Long,
    ): AnalysisResponse {
        val analysis = requireOwned(findUser(username), id)
        val result = objectMapper.readValue<AnalysisResult>(analysis.resultJson)
        // 끼니 목록에서 N+1을 만들지 않도록 URL은 한 번에 받는다.
        val urls = fileService.getPresignedUrls(result.photos.map { it.fileId })
        return AnalysisResponse(
            id = analysis.requiredId,
            status = analysis.status,
            photos =
                result.photos.map {
                    AnalysisPhotoResponse(
                        fileId = it.fileId,
                        url = urls[it.fileId],
                        failed = it.failed,
                        items = it.items,
                    )
                },
        )
    }

    @Transactional
    fun retry(
        username: String,
        id: Long,
    ) {
        // create와 같은 가드다 — 클라이언트가 없으면 `retryFailed`가 사진을 다시 실패로 표시하는
        // 것밖에 못 하는데 여기서 204를 돌려주면, 앱은 성공으로 알고 폴링하다 또 실패를 본다.
        // FAILED에서 재시도 버튼을 띄우는 화면과 맞물려 **빠져나갈 수 없는 고리**가 된다.
        if (!analyzer.isAvailable) throw CustomException(DietErrorCode.LLM_UNAVAILABLE)

        val analysis = requireOwned(findUser(username), id)
        // markPending()은 status만 바꾸고 resultJson의 failed 표시는 그대로 둔다 — 진행 중인 재인식이
        // 끝나기 전에 또 들어오면 아래 검사를 통과해 같은 사진에 유료 호출이 한 번 더 나간다.
        // `MealService.retryFeedback`이 이미 같은 모양으로 막고 있다.
        if (analysis.status == AnalysisStatus.PENDING) throw CustomException(DietErrorCode.ANALYSIS_IN_PROGRESS, id)
        val result = objectMapper.readValue<AnalysisResult>(analysis.resultJson)
        if (result.photos.none { it.failed }) throw CustomException(DietErrorCode.ANALYSIS_NOT_RETRYABLE, id)
        analysis.markPending()
        runAfterCommit { analyzer.retryFailed(id) }
    }

    /** 확인 취소 — 레코드만 지운다. 사진은 attach된 적이 없어 TEMP로 남고 파일 정리 배치가 수거한다. */
    @Transactional
    fun delete(
        username: String,
        id: Long,
    ) {
        repository.delete(requireOwned(findUser(username), id))
    }

    fun requireOwned(
        user: User,
        id: Long,
    ): MealAnalysis {
        val analysis =
            repository.findByIdOrNull(id)
                ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        if (analysis.user.requiredId != user.requiredId) {
            throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        }
        return analysis
    }

    private fun findUser(username: String): User =
        userRepository.findByUsername(username)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)

    companion object {
        /** 상한 5장은 초기 추정치다 — 실제로 평균 몇 장을 올리는지 관찰해 조정한다. */
        const val MAX_PHOTOS = 5
    }
}
