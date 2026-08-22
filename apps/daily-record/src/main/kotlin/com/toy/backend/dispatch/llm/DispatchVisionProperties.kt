package com.toy.backend.dispatch.llm

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * **식단용 `openrouter.*`와 분리한다.** 식단 인식은 `gemini-2.5-flash`로 잘 돌고 있고,
 * 배차표 판독 때문에 그쪽 비용을 올릴 이유가 없다.
 *
 * 표 판독에서 `2.5-flash`는 **같은 사진도 호출마다 답이 달라진다**(실측 5회에 5/9~9/9).
 * `2.5-pro`도 흔들렸다. 그래서 `3.6-flash`를 골랐고(6회 연속 정답과 일치), 이후 같은 계열의
 * **`3.7-flash`가 절반 값에 나와 운영을 그쪽으로 옮겼다.** 기본값이 이 사실을 따라간다.
 *
 * **관리비(`maintenance.*`)와 값이 같아졌지만 설정은 합치지 않는다.** 배차표가 더 어려운
 * 판독이라 문제가 생기면 한쪽만 되돌릴 수 있어야 한다.
 */
@ConfigurationProperties(prefix = "dispatch")
data class DispatchVisionProperties(
    val apiKey: String = "",
    val baseUrl: String = "https://openrouter.ai/api/v1",
    val visionModel: String = "google/gemini-3.7-flash",
    /**
     * **reasoning 토큰이 이 한도에 함께 잡힌다.** `3.7-flash`는 조각 하나에
     * 1,300~3,000을 쓰므로 식단용 기본값(4,000)으로 두면 `content`가 빈 채로 온다.
     */
    val visionMaxTokens: Int = 30000,
    val timeoutSeconds: Long = 120,
    /**
     * 전체본에서 행을 찾을 때만 쓰는 대상 이름. **DB에도 응답에도 저장하지 않는다** —
     * 조회 API가 무인증으로 열려 있다.
     */
    val fatherName: String = "",
)
