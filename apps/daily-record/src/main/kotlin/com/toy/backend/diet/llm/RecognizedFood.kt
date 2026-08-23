package com.toy.backend.diet.llm

/**
 * **모든 수치가 「1인분」 기준이다.** 사진 전체 기준이 아니다 — 실제로 먹은 양은 `portion`을
 * 곱해서 구한다. 기준을 하나로 묶지 않으면 수량과 영양소가 따로 논다: 예전에는 수량이
 * 「고정 200g × portion」이고 영양소는 모델이 사진 전체를 보고 부른 값이라, 치킨 한 상자가
 * `200g / 2500kcal / 탄120 단170 지150`처럼 **200g 안에 매크로 440g이 든** 결과가 나왔다.
 */
data class RecognizedFood(
    /** 한국어 음식명 */
    val name: String,
    /** 1인분 대비 배수 (0.5 = 반 인분, 4 = 4인분) */
    val portion: Double,
    /** 이 음식 **1인분**의 중량(g). 식품DB 매칭이 실패했을 때 수량의 근거가 된다. */
    val servingWeightG: Double,
    val estimatedKcal: Double,
    val estimatedCarbsG: Double,
    val estimatedProteinG: Double,
    val estimatedFatG: Double,
    val estimatedSugarG: Double,
    val estimatedSodiumMg: Double,
    val estimatedFiberG: Double,
)

/**
 * OpenRouter `messages` 배열의 한 칸. **`role`은 API 값**(`"user"`/`"assistant"`)이다 —
 * 도메인 enum(`ChatRole`)을 여기 두면 `diet.llm`이 `diet.chat`을 알게 되어 의존이 뒤집힌다.
 * 변환은 부르는 쪽에서 한다.
 */
data class ChatTurn(
    val role: String,
    val content: String,
)
