package com.toy.backend.diet.llm

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import tools.jackson.databind.JsonNode

private val log = KotlinLogging.logger {}

data class RecognizedFood(
    /** 한국어 음식명 */
    val name: String,
    /** 1인분 대비 배수 (0.5 = 반 인분) */
    val portion: Double,
    val estimatedKcal: Double,
    val estimatedCarbsG: Double,
    val estimatedProteinG: Double,
    val estimatedFatG: Double,
)

/**
 * OpenRouter `chat/completions` 래퍼. **영양소 수치나 점수를 LLM에게 묻지 않는다** —
 * 같은 사진에서도 호출마다 값이 달라지기 때문이다. 여기서 얻는 것은 *음식 식별*과 *문장*뿐이고,
 * `estimated*` 값은 식품DB 매칭이 실패했을 때만 쓰는 fallback이다.
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
        val body =
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
            )

        val content = post(body) ?: return null
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
    ): String? {
        val body =
            mapOf(
                "model" to properties.textModel,
                "messages" to
                    listOf(
                        mapOf("role" to "system", "content" to systemPrompt),
                        mapOf("role" to "user", "content" to userPrompt),
                    ),
            )
        return post(body)?.trim()?.takeIf { it.isNotBlank() }
    }

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
            response
                ?.path("choices")
                ?.path(0)
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
                estimatedKcal = item.path("estimatedKcal").asDouble(),
                estimatedCarbsG = item.path("estimatedCarbsG").asDouble(),
                estimatedProteinG = item.path("estimatedProteinG").asDouble(),
                estimatedFatG = item.path("estimatedFatG").asDouble(),
            )
        }
    }

    companion object {
        private const val VISION_PROMPT =
            "사진 속 음식을 하나씩 식별해 주세요. 각 음식의 한국어 이름과, 1인분 대비 양(portion, 0.5는 반 인분), " +
                "그리고 대략적인 영양소 추정치를 알려 주세요. 음식이 아닌 물건은 넣지 마세요."

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
                                                                "portion" to NUMBER,
                                                                "estimatedKcal" to NUMBER,
                                                                "estimatedCarbsG" to NUMBER,
                                                                "estimatedProteinG" to NUMBER,
                                                                "estimatedFatG" to NUMBER,
                                                            ),
                                                        "required" to
                                                            listOf(
                                                                "name",
                                                                "portion",
                                                                "estimatedKcal",
                                                                "estimatedCarbsG",
                                                                "estimatedProteinG",
                                                                "estimatedFatG",
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
