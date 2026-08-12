package com.toy.backend.dispatch

import com.toy.backend.common.constant.Code
import org.springframework.http.HttpStatus

enum class DispatchErrorCode(
    private val httpStatus: HttpStatus,
    private val message: String,
) : Code {
    // 잘린 사진에는 성명 컬럼이 없어 행 위치를 알 수 없다. 추측해서 저장하느니 거부한다.
    ROSTER_NOT_FOUND(HttpStatus.BAD_REQUEST, "%s 배차표 기준이 없습니다. 성명 컬럼이 보이는 사진을 먼저 올려 주세요."),

    // 성명 컬럼은 보이는데 그 안에 대상이 없다. 저장된 행 위치로 폴백하면 다른 기사의 근무가 들어온다.
    TARGET_NOT_FOUND(HttpStatus.BAD_REQUEST, "%s 배차표 사진에서 대상 기사를 찾지 못했습니다. 사진을 확인해 주세요."),
    TARGET_NAME_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "dispatch.father-name이 설정되지 않았습니다."),
    VISION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "사진 인식에 실패했습니다. 잠시 후 다시 시도해 주세요."),
    IMAGE_UNREADABLE(HttpStatus.BAD_REQUEST, "이미지를 읽을 수 없습니다."),

    // 하루 편집(`PUT /dispatch/shifts/{date}`)의 역할·필드 조합 검증.
    // 빈 검증 애너테이션으로 표현되지 않아 서비스 진입부에서 명시적으로 던진다.
    DAY_EDIT_ROLE_REQUIRED(HttpStatus.BAD_REQUEST, "고칠 근무가 없습니다. 아빠 또는 엄마 중 하나는 보내 주세요."),
    MOTHER_SLOT_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "엄마 근무에는 순번 대신 근무조(A·B·C)를 보내 주세요."),
    FATHER_SLOT_CODE_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "아빠 근무에는 근무조 대신 순번을 보내 주세요."),
    ;

    override fun getHttpStatus(): HttpStatus = httpStatus

    override fun getMessage(): String = message

    override fun getStatusName(): String = httpStatus.name

    override fun getCodeName(): String = name
}
