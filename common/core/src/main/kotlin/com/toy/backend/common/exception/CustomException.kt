package com.toy.backend.common.exception

import com.toy.backend.common.constant.ErrorCode

class CustomException(
    val errorCode: ErrorCode,
    vararg val params: Any?,
) : RuntimeException(formatMessage(errorCode, params)) {
    companion object {
        private fun formatMessage(
            errorCode: ErrorCode,
            params: Array<out Any?>,
        ): String {
            if (params.isEmpty()) return errorCode.getMessage()
            return try {
                String.format(errorCode.getMessage(), *params)
            } catch (_: Exception) {
                "${errorCode.getMessage()} (format params: ${params.contentToString()})"
            }
        }
    }
}
