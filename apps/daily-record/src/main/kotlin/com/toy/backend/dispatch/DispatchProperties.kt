package com.toy.backend.dispatch

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 배차표 전용 설정. 판독 모델·키는 `vision.*`가 공통으로 들고 있고, 여기에는 배차표에만
 * 필요한 값만 남는다.
 */
@ConfigurationProperties(prefix = "dispatch")
data class DispatchProperties(
    /**
     * 전체본에서 행을 찾을 때만 쓰는 대상 이름. **DB에도 응답에도 저장하지 않는다** —
     * 조회 API가 무인증으로 열려 있다.
     */
    val fatherName: String = "",
)
