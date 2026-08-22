package com.toy.backend.maintenance.llm

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * **배차(`dispatch.*`)와 값이 같아졌지만 설정은 합치지 않는다.** 두 기능은 각자 자기 도메인
 * 설정을 달고 있고(`dispatch.father-name`), 무엇보다 한쪽만 되돌릴 수 있어야 한다.
 * 배차표는 빈 칸이 절반 이상이고 집계 컬럼이 끼어드는 더 어려운 판독이라, 관리비에서 잘
 * 나온 모델이 배차에서도 잘 나온다는 보장이 없다.
 *
 * 영수증 8장 × 2회 실측에서 `3.7-flash`가 합계 검증 16/16, 실행 간 완전일치 8/8이었다.
 * 장당 $0.004다.
 */
@ConfigurationProperties(prefix = "maintenance")
data class MaintenanceVisionProperties(
    val apiKey: String = "",
    val baseUrl: String = "https://openrouter.ai/api/v1",
    val visionModel: String = "google/gemini-3.7-flash",
    /**
     * **reasoning 토큰이 이 한도에 함께 잡힌다.** 실측 completion이 1,773~2,333이라
     * 식단용 기본값(4,000)으로 두면 `content`가 빈 채로 온다.
     */
    val visionMaxTokens: Int = 30000,
    val timeoutSeconds: Long = 120,
)
