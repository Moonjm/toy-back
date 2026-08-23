package com.toy.backend.vision

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 사진 판독용 OpenRouter 설정. **배차표와 관리비 고지서가 함께 쓴다.**
 *
 * 한때 `dispatch.*`와 `maintenance.*`로 나눠 두었다. 배차표가 더 어려운 판독이라 문제가 생기면
 * 한쪽만 되돌리려는 것이었는데, 두 기능이 같은 모델(`3.7-flash`)로 굳으면서 얻는 것보다 치르는
 * 값이 커졌다 — 키를 한쪽에만 넣어 다른 쪽이 401을 내는 사고가 실제로 났다.
 *
 * **식단용 `openrouter.*`와는 여전히 분리한다.** 그쪽은 `2.5-flash`에 텍스트 모델까지 따로 쓴다.
 *
 * 표 판독에서 `2.5-flash`는 같은 사진도 호출마다 답이 달라진다(배차표 실측 5회에 5/9~9/9).
 * `3.6-flash`가 6회 연속 정답과 일치했고, 같은 계열의 `3.7-flash`가 절반 값에 나와 그쪽으로 옮겼다.
 * 관리비 영수증에서는 8장 × 2회에 합계 검증 16/16, 실행 간 완전일치 8/8이었다.
 */
@ConfigurationProperties(prefix = "vision")
data class VisionProperties(
    val apiKey: String = "",
    val baseUrl: String = "https://openrouter.ai/api/v1",
    val visionModel: String = "google/gemini-3.7-flash",
    /**
     * **reasoning 토큰이 이 한도에 함께 잡힌다.** 배차표는 조각당 1,300~3,000, 관리비는
     * 1,773~2,333을 쓴다. 식단용 기본값(4,000)으로 두면 `content`가 빈 채로 온다.
     *
     * 아예 안 보내면 OpenRouter가 모델 최대 출력만큼 잔액을 선점해 잔액이 남았는데도 402가 난다.
     */
    val visionMaxTokens: Int = 30000,
    val timeoutSeconds: Long = 120,
)
