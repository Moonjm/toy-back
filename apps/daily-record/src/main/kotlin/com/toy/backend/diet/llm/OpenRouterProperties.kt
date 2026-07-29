package com.toy.backend.diet.llm

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 모델은 환경변수로만 정한다 — 코드에 박지 않아야 한식 인식 정확도를 모델별로 비교하며 교체할 수 있다.
 * 교체할 때는 **`json_schema` strict를 지원하는 모델인지 반드시 확인한다**(미지원 모델은 파싱이 불안정하다).
 */
@ConfigurationProperties(prefix = "openrouter")
data class OpenRouterProperties(
    val apiKey: String = "",
    val baseUrl: String = "https://openrouter.ai/api/v1",
    /** 음식 식별은 정확도가 결과 전체를 좌우한다 */
    val visionModel: String = "google/gemini-2.5-flash",
    /** 피드백은 수치를 다 넘겨받아 문장만 만드는 쉬운 작업이라 더 싼 모델로 충분하다 */
    val textModel: String = "google/gemini-2.5-flash-lite",
    val timeoutSeconds: Long = 60,
)
