package com.example.backend.common.response

import com.example.backend.common.constant.SuccessCode

class DataResponseBody<T>(
    val data: T?,
) : ResponseBody(SuccessCode.SUCCESS.getHttpStatus().value(), SuccessCode.SUCCESS.getMessage())
