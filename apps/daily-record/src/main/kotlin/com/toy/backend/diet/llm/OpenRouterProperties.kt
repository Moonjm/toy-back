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
    /**
     * 응답 최대 토큰. **반드시 보낸다 — 안 보내면 잔액이 남았는데도 402가 난다.**
     *
     * OpenRouter는 호출 전에 「최대로 들 수 있는 비용」만큼 잔액을 선점하는데, `max_tokens`가
     * 없으면 모델의 최대 출력값을 쓴다. `google/gemini-2.5-flash`는 그 값이 65,535이고
     * 출력 단가가 토큰당 $0.0000025라 **한 번 호출에 $0.1638을 선점한다.** 실제로 잔액
     * $0.16에서 402가 났고, 두 숫자가 맞아떨어진다.
     *
     * 4,000이면 선점액이 $0.01이다. 음식 8개짜리 JSON이 1,000토큰 남짓이라 여유가 넉넉하다.
     *
     * **너무 줄이면 조용히 깨진다** — Gemini 2.5 계열은 생각(reasoning) 토큰이 이 한도에
     * 함께 잡혀서, 한도를 다 쓰면 `content`가 빈 채로 온다. 그래서 `finish_reason`이
     * `length`면 따로 로그를 남긴다(`OpenRouterClient`).
     */
    val visionMaxTokens: Int = 4000,
    /** 피드백은 문단 하나라 훨씬 작다. lite 모델 단가로 선점액이 $0.0008이다. */
    val textMaxTokens: Int = 2000,
    val timeoutSeconds: Long = 60,
)
