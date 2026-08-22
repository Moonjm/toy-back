package com.toy.backend.maintenance

import com.toy.backend.common.constant.Code
import org.springframework.http.HttpStatus

enum class MaintenanceErrorCode(
    private val httpStatus: HttpStatus,
    private val message: String,
) : Code {
    BILL_NOT_FOUND(HttpStatus.NOT_FOUND, "%s 관리비 내역이 없습니다."),

    // 조용히 덮어쓰면 검수를 마친 값이 인식 직후 값으로 되돌아간다. 고칠 때는 수정을 쓴다.
    BILL_ALREADY_EXISTS(HttpStatus.CONFLICT, "%s 관리비 내역이 이미 있습니다. 고치려면 수정해 주세요."),
    VISION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "고지서 인식에 실패했습니다. 잠시 후 다시 시도해 주세요."),
    IMAGE_REQUIRED(HttpStatus.BAD_REQUEST, "이미지가 비어 있습니다."),
    ;

    override fun getHttpStatus(): HttpStatus = httpStatus

    override fun getMessage(): String = message

    override fun getStatusName(): String = httpStatus.name

    override fun getCodeName(): String = name
}
