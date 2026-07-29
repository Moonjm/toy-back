package com.toy.backend.diet

import com.toy.backend.common.constant.Code
import org.springframework.http.HttpStatus

enum class DietErrorCode(
    private val httpStatus: HttpStatus,
    private val message: String,
) : Code {
    PROFILE_NOT_FOUND(HttpStatus.NOT_FOUND, "식단 프로필이 없습니다. 프로필을 먼저 저장해 주세요."),
    PHOTO_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "끼니당 사진은 최대 %s장입니다."),
    LLM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "LLM 연동이 설정되지 않았습니다. openrouter.api-key를 설정해 주세요."),
    ANALYSIS_NOT_CONFIRMABLE(HttpStatus.BAD_REQUEST, "인식이 끝나지 않은 분석은 확정할 수 없습니다: %s"),
    ANALYSIS_NOT_RETRYABLE(HttpStatus.BAD_REQUEST, "재인식할 실패한 사진이 없습니다: %s"),
    FEEDBACK_NOT_RETRYABLE(HttpStatus.BAD_REQUEST, "실패 상태의 끼니만 피드백을 재생성할 수 있습니다: %s"),
    ;

    override fun getHttpStatus(): HttpStatus = httpStatus

    override fun getMessage(): String = message

    override fun getStatusName(): String = httpStatus.name

    override fun getCodeName(): String = name
}
