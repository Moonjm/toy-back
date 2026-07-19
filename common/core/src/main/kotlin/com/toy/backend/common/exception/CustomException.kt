package com.toy.backend.common.exception

import com.toy.backend.common.constant.Code

class CustomException(
    val errorCode: Code,
    vararg val params: Any?,
) : RuntimeException(formatMessage(errorCode, params)) {
    companion object {
        private fun formatMessage(
            errorCode: Code,
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
