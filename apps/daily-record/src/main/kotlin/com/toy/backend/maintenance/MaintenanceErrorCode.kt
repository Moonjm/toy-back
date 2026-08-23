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

    // PUT은 path의 연월로 레코드를 찾는다. 본문의 yearMonth를 무시하고 그대로 저장하면,
    // 검수 화면이 다른 달의 인식 결과를 실은 채 이 주소로 보낼 때 엉뚱한 달의 값으로
    // 통째로 덮인다 — create의 409가 막아 둔 조용한 덮어쓰기가 PUT으로 다시 열리는 셈이다.
    YEAR_MONTH_MISMATCH(HttpStatus.BAD_REQUEST, "주소의 연월(%s)과 본문의 연월(%s)이 다릅니다."),
    VISION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "고지서 인식에 실패했습니다. 잠시 후 다시 시도해 주세요."),
    IMAGE_REQUIRED(HttpStatus.BAD_REQUEST, "이미지가 비어 있습니다."),
    ;

    override fun getHttpStatus(): HttpStatus = httpStatus

    override fun getMessage(): String = message

    override fun getStatusName(): String = httpStatus.name

    override fun getCodeName(): String = name
}
