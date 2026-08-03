package com.toy.backend.diet.llm

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import tools.jackson.databind.JsonNode

private val log = KotlinLogging.logger {}

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
 * OpenRouter `chat/completions` 래퍼. **매칭에 성공한 음식의 수치는 LLM에게 묻지 않는다** —
 * 같은 사진에서도 호출마다 값이 달라지기 때문이다. 여기서 얻는 것은 *음식 식별*과 *문장*이고,
 * `estimated*` 값은 식품DB 매칭이 실패했을 때만 쓰는 fallback이다.
 *
 * 그 fallback에 당류·나트륨·식이섬유도 들어간다. 빼고 0으로 두면 「없음」이 아니라 **합계에
 * 더해지는 틀린 숫자**가 되고, 그 오차가 늘 안전한 쪽으로 기울어 나트륨 경고만 안 뜬다.
 *
 * 호출 실패는 전부 null로 돌려준다 — 자동 재시도를 하지 않는다(실패 반복이 곧 비용 폭주 경로).
 */
class OpenRouterClient(
    private val properties: OpenRouterProperties,
    private val webClient: WebClient,
) {
    fun recognizeFoods(
        base64Image: String,
        contentType: String,
    ): List<RecognizedFood>? {
        val content = post(visionBody(base64Image, contentType)) ?: return null
        return try {
            parseItems(content)
        } catch (e: Exception) {
            log.error(e) { "음식 인식 응답 파싱 실패: $content" }
            null
        }
    }

    fun generateText(
        systemPrompt: String,
        userPrompt: String,
    ): String? = post(textBody(systemPrompt, userPrompt))?.trim()?.takeIf { it.isNotBlank() }

    /** 본문 구성만 떼어 둔다 — `max_tokens`가 빠지면 402가 나는데, 그건 실기동에서만 드러난다. */
    internal fun visionBody(
        base64Image: String,
        contentType: String,
    ): Map<String, Any> =
        mapOf(
            "model" to properties.visionModel,
            "messages" to
                listOf(
                    mapOf(
                        "role" to "user",
                        "content" to
                            listOf(
                                mapOf("type" to "text", "text" to VISION_PROMPT),
                                mapOf(
                                    "type" to "image_url",
                                    "image_url" to mapOf("url" to "data:$contentType;base64,$base64Image"),
                                ),
                            ),
                    ),
                ),
            "response_format" to RESPONSE_FORMAT,
            // 안 보내면 잔액이 남았는데도 402가 난다(`OpenRouterProperties.visionMaxTokens`).
            "max_tokens" to properties.visionMaxTokens,
        )

    internal fun textBody(
        systemPrompt: String,
        userPrompt: String,
    ): Map<String, Any> =
        mapOf(
            "model" to properties.textModel,
            "messages" to
                listOf(
                    mapOf("role" to "system", "content" to systemPrompt),
                    mapOf("role" to "user", "content" to userPrompt),
                ),
            "max_tokens" to properties.textMaxTokens,
        )

    /** `choices[0].message.content` 문자열을 꺼낸다. 실패는 로그를 남기고 null. */
    private fun post(body: Map<String, Any>): String? =
        try {
            val response =
                webClient
                    .post()
                    .uri("/chat/completions")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono<JsonNode>()
                    .block()
            val choice = response?.path("choices")?.path(0)
            // **한도에 걸린 것과 그냥 실패한 것을 구분해 남긴다.** Gemini 2.5 계열은 생각(reasoning)
            // 토큰이 `max_tokens`에 함께 잡혀서, 한도를 다 쓰면 `content`가 빈 채로 온다.
            // 이 로그가 없으면 「모델이 이상하다」로 오해하고 한도를 못 찾는다.
            if (choice?.path("finish_reason")?.asString() == FINISH_REASON_LENGTH) {
                log.error { "OpenRouter 응답이 max_tokens에 걸려 잘렸다 — 한도를 올려야 한다: $choice" }
            }
            choice
                ?.path("message")
                ?.path("content")
                ?.asString()
                ?.takeIf { it.isNotBlank() }
                ?: run {
                    log.error { "OpenRouter 응답에 content가 없다: $response" }
                    null
                }
        } catch (e: Exception) {
            log.error(e) { "OpenRouter 호출 실패" }
            null
        }

    private fun parseItems(content: String): List<RecognizedFood> {
        // strict json_schema 응답이라 형태가 고정이다. JsonNode로 직접 읽어 매퍼 설정 의존을 없앤다.
        val root =
            tools.jackson.databind.json.JsonMapper
                .builder()
                .build()
                .readTree(content)
        // JsonNode 자체에 `<R> R map(Function)`(단건용) 멤버가 있어 Iterable.map과 이름이 겹친다.
        // Iterable<JsonNode>로 명시해 컬렉션용 map이 뽑히도록 한다.
        val items: Iterable<JsonNode> = root.path("items")
        return items.map { item ->
            RecognizedFood(
                name = item.path("name").asString(),
                portion = item.path("portion").asDouble(),
                servingWeightG = item.path("servingWeightG").asDouble(),
                estimatedKcal = item.path("estimatedKcal").asDouble(),
                estimatedCarbsG = item.path("estimatedCarbsG").asDouble(),
                estimatedProteinG = item.path("estimatedProteinG").asDouble(),
                estimatedFatG = item.path("estimatedFatG").asDouble(),
                estimatedSugarG = item.path("estimatedSugarG").asDouble(),
                estimatedSodiumMg = item.path("estimatedSodiumMg").asDouble(),
                estimatedFiberG = item.path("estimatedFiberG").asDouble(),
            )
        }
    }

    companion object {
        /** OpenAI 호환 응답의 절단 신호. Gemini는 네이티브로 `MAX_TOKENS`를 주지만 이 값으로 정규화돼 온다. */
        private const val FINISH_REASON_LENGTH = "length"

        private const val VISION_PROMPT =
            "사진 속 음식을 하나씩 식별해 주세요. 음식이 아닌 물건은 넣지 마세요.\n" +
                "각 음식마다 아래를 알려 주세요.\n" +
                "① name — 한국어 음식명\n" +
                "② servingWeightG — 그 음식 **1인분**의 중량(g). 한 사람이 한 번에 먹는 양이며, " +
                "포장 단위나 사진에 보이는 전체 양이 아닙니다. 치킨이면 2~3조각, 라면이면 1봉지입니다.\n" +
                "③ portion — 사진에 **몇 인분**이 보이는지 (0.5는 반 인분, 4는 4인분)\n" +
                "④ estimated* — **1인분 기준** 영양소. 사진 전체 기준이 아닙니다. " +
                "당류·나트륨·식이섬유도 그 음식의 통상적인 조리법을 기준으로 추정해 주세요" +
                "(모르겠으면 0 대신 비슷한 음식의 값을 쓰세요).\n" +
                "탄수화물+단백질+지방의 합은 servingWeightG를 넘을 수 없습니다."

        private val NUMBER = mapOf("type" to "number")

        private val RESPONSE_FORMAT =
            mapOf(
                "type" to "json_schema",
                "json_schema" to
                    mapOf(
                        "name" to "meal_items",
                        "strict" to true,
                        "schema" to
                            mapOf(
                                "type" to "object",
                                "properties" to
                                    mapOf(
                                        "items" to
                                            mapOf(
                                                "type" to "array",
                                                "items" to
                                                    mapOf(
                                                        "type" to "object",
                                                        "properties" to
                                                            mapOf(
                                                                "name" to mapOf("type" to "string"),
                                                                "servingWeightG" to NUMBER,
                                                                "portion" to NUMBER,
                                                                "estimatedKcal" to NUMBER,
                                                                "estimatedCarbsG" to NUMBER,
                                                                "estimatedProteinG" to NUMBER,
                                                                "estimatedFatG" to NUMBER,
                                                                "estimatedSugarG" to NUMBER,
                                                                "estimatedSodiumMg" to NUMBER,
                                                                "estimatedFiberG" to NUMBER,
                                                            ),
                                                        "required" to
                                                            listOf(
                                                                "name",
                                                                "servingWeightG",
                                                                "portion",
                                                                "estimatedKcal",
                                                                "estimatedCarbsG",
                                                                "estimatedProteinG",
                                                                "estimatedFatG",
                                                                // strict: true라 properties에 있는 키가
                                                                // required에 없으면 호출 자체가 거부된다.
                                                                "estimatedSugarG",
                                                                "estimatedSodiumMg",
                                                                "estimatedFiberG",
                                                            ),
                                                        "additionalProperties" to false,
                                                    ),
                                            ),
                                    ),
                                "required" to listOf("items"),
                                "additionalProperties" to false,
                            ),
                    ),
            )
    }
}
