package com.toy.backend.dispatch

import com.toy.backend.common.constant.Code
import org.springframework.http.HttpStatus

enum class DispatchErrorCode(
    private val httpStatus: HttpStatus,
    private val message: String,
) : Code {
    // 잘린 사진에는 성명 컬럼이 없어 행 위치를 알 수 없다. 추측해서 저장하느니 거부한다.
    ROSTER_NOT_FOUND(HttpStatus.BAD_REQUEST, "%s 배차표 기준이 없습니다. 성명 컬럼이 보이는 사진을 먼저 올려 주세요."),
    TARGET_NAME_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "dispatch.father-name이 설정되지 않았습니다."),
    VISION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "사진 인식에 실패했습니다. 잠시 후 다시 시도해 주세요."),
    IMAGE_UNREADABLE(HttpStatus.BAD_REQUEST, "이미지를 읽을 수 없습니다."),

    // 조회는 무인증으로 열려 있다. 상한이 없으면 요청 한 번으로 수백만 건을 만들어 힙이 터진다.
    INVALID_RANGE(HttpStatus.BAD_REQUEST, "조회 기간이 올바르지 않습니다. 시작일이 종료일보다 앞서야 하고 최대 %s일까지 조회할 수 있습니다."),
    ;

    override fun getHttpStatus(): HttpStatus = httpStatus

    override fun getMessage(): String = message

    override fun getStatusName(): String = httpStatus.name

    override fun getCodeName(): String = name
}
