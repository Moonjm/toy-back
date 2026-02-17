package com.example.backend.common.constant

import org.springframework.http.HttpStatus

enum class ErrorCode(
    private val httpStatus: HttpStatus,
    private val message: String,
) : Code {
    DUPLICATE_RESOURCE_ID(HttpStatus.BAD_REQUEST, "중복된 리소스 ID가 포함되어 있습니다."),
    DUPLICATE_RESOURCE(HttpStatus.BAD_REQUEST, "중복된 리소스입니다: %s"),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다: %s"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "리소스를 찾을 수 없습니다: %s"),
    ALREADY_PAIRED(HttpStatus.BAD_REQUEST, "이미 페어가 연결되어 있습니다: %s"),
    PAIR_NOT_CONNECTED(HttpStatus.BAD_REQUEST, "페어가 연결되지 않았습니다."),
    FAMILY_TREE_ACCESS_DENIED(HttpStatus.FORBIDDEN, "가계도에 대한 접근 권한이 없습니다."),
    FAMILY_TREE_OWNER_REQUIRED(HttpStatus.FORBIDDEN, "가계도 소유자만 수행할 수 있는 작업입니다."),
    FAMILY_TREE_LAST_OWNER(HttpStatus.BAD_REQUEST, "마지막 소유자는 제거할 수 없습니다."),
    ALREADY_HAS_SPOUSE(HttpStatus.BAD_REQUEST, "이미 배우자가 있습니다: %s"),
    MAX_PARENTS_EXCEEDED(HttpStatus.BAD_REQUEST, "부모는 최대 2명까지만 가능합니다."),
    CIRCULAR_RELATIONSHIP(HttpStatus.BAD_REQUEST, "순환 관계가 감지되었습니다."),
    SELF_RELATIONSHIP(HttpStatus.BAD_REQUEST, "자기 자신과의 관계는 설정할 수 없습니다."),
    INVALID_DATE_RANGE(HttpStatus.BAD_REQUEST, "생년월일이 사망일보다 이후일 수 없습니다."),
    ;

    override fun getHttpStatus(): HttpStatus = httpStatus

    override fun getMessage(): String = message

    override fun getStatusName(): String = httpStatus.name
}
