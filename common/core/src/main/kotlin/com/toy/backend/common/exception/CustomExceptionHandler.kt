package com.toy.backend.common.exception

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.response.ErrorResponseBody
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.persistence.EntityNotFoundException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.sql.SQLException

private val log = KotlinLogging.logger {}

@RestControllerAdvice
class CustomExceptionHandler {
    companion object {
        private val IGNORE_PATHS = listOf("stomp/publish", "favicon.ico")
        private val KNOWN_INTEGRITY_SQL_STATES = setOf("23505", "23503", "23502")
    }

    @ExceptionHandler(Exception::class)
    fun handleException(e: Exception): ResponseEntity<ErrorResponseBody> =
        ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(
                ErrorResponseBody(
                    status = HttpStatus.INTERNAL_SERVER_ERROR,
                    message = "서버 내부 오류가 발생했습니다.",
                    code = HttpStatus.INTERNAL_SERVER_ERROR.value().toString(),
                    error = HttpStatus.INTERNAL_SERVER_ERROR.name,
                ),
            ).also { log.error(e) { "Unhandled Exception" } }

    @ExceptionHandler(CustomException::class)
    fun handleCustomException(e: CustomException): ResponseEntity<ErrorResponseBody> =
        ResponseEntity
            .status(e.errorCode.getHttpStatus())
            .body(
                ErrorResponseBody(
                    status = e.errorCode.getHttpStatus(),
                    message = e.message,
                    code =
                        e.errorCode
                            .getHttpStatus()
                            .value()
                            .toString(),
                    error = e.errorCode.getCodeName(),
                ),
            ).also {
                // 4xx 는 클라이언트 요인이고 원인 상세는 던진 쪽에서 이미 로깅한다 — 여기서 또 error+스택을 남기면
                // 같은 실패가 2회 기록되고 모니터링 노이즈가 된다. 5xx(서버 결함)만 error 로 남긴다.
                if (e.errorCode.getHttpStatus().is5xxServerError) {
                    log.error(e) { "CustomException: ${e.message}" }
                } else {
                    log.warn { "CustomException: ${e.message}" }
                }
            }

    @ExceptionHandler(EntityNotFoundException::class)
    fun handleEntityNotFoundException(e: EntityNotFoundException): ResponseEntity<ErrorResponseBody> =
        ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(
                ErrorResponseBody(
                    status = HttpStatus.NOT_FOUND,
                    message = e.message,
                    code = HttpStatus.NOT_FOUND.value().toString(),
                    error = HttpStatus.NOT_FOUND.name,
                ),
            ).also { log.warn { "EntityNotFoundException: ${e.message}" } }

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResourceFoundException(
        e: NoResourceFoundException,
        request: HttpServletRequest?,
    ): ResponseEntity<ErrorResponseBody> =
        ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(
                ErrorResponseBody(
                    status = HttpStatus.NOT_FOUND,
                    message = "해당 경로를 찾지 못했습니다. url 을 확인해주세요",
                    code = HttpStatus.NOT_FOUND.value().toString(),
                    error = HttpStatus.NOT_FOUND.name,
                ),
            ).also {
                if (IGNORE_PATHS.none { request?.requestURI?.contains(it) == true }) {
                    log.warn { "NoResourceFoundException: ${request?.requestURI}" }
                }
            }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleMethodArgumentNotValid(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponseBody> {
        val fieldErrors = e.bindingResult.fieldErrors
        val errorMessage =
            fieldErrors.joinToString { error: FieldError -> "${error.field}: ${error.defaultMessage}" }
        log.warn { "Validation Error: $errorMessage" }

        return ResponseEntity(
            ErrorResponseBody(
                status = HttpStatus.BAD_REQUEST,
                message = errorMessage,
                code = HttpStatus.BAD_REQUEST.value().toString(),
                error = HttpStatus.BAD_REQUEST.name,
            ),
            HttpStatus.BAD_REQUEST,
        )
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadableException(e: HttpMessageNotReadableException): ResponseEntity<ErrorResponseBody> =
        ResponseEntity(
            ErrorResponseBody(
                status = HttpStatus.BAD_REQUEST,
                message =
                    e.message
                        ?.takeIf { "Required request body is missing" in it }
                        ?.let { "필수 요청 본문(Request Body)이 누락되었습니다." }
                        ?: "필수 요청 본문이 누락되었거나 형식이 잘못되었습니다.",
                code = HttpStatus.BAD_REQUEST.value().toString(),
                error = HttpStatus.BAD_REQUEST.name,
            ),
            HttpStatus.BAD_REQUEST,
        ).also { log.warn { "HttpMessageNotReadableException: ${e.message}" } }

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingServletRequestParameterException(e: MissingServletRequestParameterException): ResponseEntity<ErrorResponseBody> =
        ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(
                ErrorResponseBody(
                    status = HttpStatus.BAD_REQUEST,
                    message = "필수 요청 파라미터(${e.parameterName})가 누락되었습니다.",
                    code = HttpStatus.BAD_REQUEST.value().toString(),
                    error = HttpStatus.BAD_REQUEST.name,
                ),
            ).also { log.warn { "MissingServletRequestParameterException: ${e.message}" } }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleMethodArgumentTypeMismatchException(e: MethodArgumentTypeMismatchException): ResponseEntity<ErrorResponseBody> =
        ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(
                ErrorResponseBody(
                    status = HttpStatus.BAD_REQUEST,
                    message = "요청 파라미터(${e.name})의 형식이 잘못되었습니다.",
                    code = HttpStatus.BAD_REQUEST.value().toString(),
                    error = HttpStatus.BAD_REQUEST.name,
                ),
            ).also { log.warn { "MethodArgumentTypeMismatchException: ${e.message}" } }

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrityViolationException(e: DataIntegrityViolationException): ResponseEntity<ErrorResponseBody> {
        val sqlState = extractSqlState(e)
        val errorCode =
            when (sqlState) {
                "23505" -> {
                    ErrorCode.DUPLICATE_RESOURCE_ID
                }

                "23503" -> {
                    if (isStillReferenced(e)) {
                        ErrorCode.RESOURCE_STILL_REFERENCED
                    } else {
                        ErrorCode.REFERENCED_RESOURCE_NOT_FOUND
                    }
                }

                "23502" -> {
                    ErrorCode.MISSING_REQUIRED_VALUE
                }

                else -> {
                    ErrorCode.DATA_INTEGRITY_VIOLATION
                }
            }

        return ResponseEntity
            .status(errorCode.getHttpStatus())
            .body(
                ErrorResponseBody(
                    status = errorCode.getHttpStatus(),
                    message = errorCode.getMessage(),
                    code = errorCode.getHttpStatus().value().toString(),
                    error = errorCode.getCodeName(),
                ),
            ).also {
                // 인식된 무결성 위반(중복 키·참조 위반·필수값 누락)은 클라이언트 요인이라 warn 으로 충분하지만,
                // 미인식 sqlState 는 스키마·드라이버 등 서버 요인일 수 있어 원인 추적용 스택트레이스를 error 로 남긴다.
                if (sqlState in KNOWN_INTEGRITY_SQL_STATES) {
                    log.warn { "DataIntegrityViolationException [$sqlState]: ${e.mostSpecificCause.message}" }
                } else {
                    log.error(e) { "DataIntegrityViolationException [$sqlState]: ${e.message}" }
                }
            }
    }

    private fun isStillReferenced(e: DataIntegrityViolationException): Boolean {
        val message = e.mostSpecificCause.message ?: return false
        return "is still referenced" in message || "여전히 참조" in message
    }

    private fun extractSqlState(e: DataIntegrityViolationException): String? {
        var cause: Throwable? = e.cause
        while (cause != null) {
            if (cause is SQLException) return cause.sqlState
            cause = cause.cause
        }
        return null
    }
}
