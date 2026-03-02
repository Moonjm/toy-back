package com.toy.backend.holidays

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "holiday.api")
data class HolidayApiProperties(
    val serviceKey: String = "",
    val baseUrl: String = "http://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService",
)
