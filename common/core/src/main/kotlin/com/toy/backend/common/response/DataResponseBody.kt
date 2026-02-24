package com.toy.backend.common.response

import com.toy.backend.common.constant.SuccessCode

class DataResponseBody<T>(
    val data: T?,
) : ResponseBody(SuccessCode.SUCCESS.getHttpStatus().value(), SuccessCode.SUCCESS.getMessage())
