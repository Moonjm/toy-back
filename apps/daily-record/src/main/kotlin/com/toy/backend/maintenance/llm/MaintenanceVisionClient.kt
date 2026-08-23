package com.toy.backend.maintenance.llm

import com.toy.backend.vision.VisionProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.bodyToMono
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

private val log = KotlinLogging.logger {}

/**
 * `post()` 한 번의 결과. **재시도할 가치가 있는 실패와 없는 실패를 구분**한다 —
 * `finish_reason == "length"`나 빈 content는 이미지·프롬프트·토큰 한도의 거의
 * 결정론적 결과라 다시 불러도 똑같지만, 네트워크 예외는 다시 불러볼 가치가 있다.
 */
private sealed interface PostOutcome {
    data class Content(
        val text: String,
    ) : PostOutcome

    data object Empty : PostOutcome

    data object Retryable : PostOutcome
}

/**
 * 관리비 영수증 한 장을 읽는다. 실패는 null로 돌려주되 **한 번은 재시도한다** —
 * 사용자가 사진을 다시 올려야 하는 대면 흐름이라 그 한 번은 서버가 삼킨다. 장당 $0.004다.
 *
 * **`3.7-flash`를 쓴다.** 영수증 8장 × 2회에서 합계 검증 16/16, 실행 간 완전일치 8/8이었다.
 * `2.5-flash`는 9회 중 1회 수도 사용량 10.8을 10.6으로 읽었는데 **그 실행도 금액 합계는
 * 통과했다** — 사용량 오독은 어떤 자동 검사에도 안 걸린다.
 */
class MaintenanceVisionClient(
    private val properties: VisionProperties,
    private val webClient: WebClient,
) {
    fun read(
        imageBase64: String,
        mediaType: String,
    ): RecognizedBill? {
        val body = visionBody(imageBase64, mediaType)
        repeat(MAX_ATTEMPTS) { attempt ->
            when (val outcome = post(body)) {
                is PostOutcome.Content -> {
                    try {
                        return parse(outcome.text)
                    } catch (e: Exception) {
                        log.warn(e) {
                            "관리비 인식 응답 파싱 실패 (${attempt + 1}/$MAX_ATTEMPTS): " +
                                outcome.text.take(LOG_CONTENT_LIMIT)
                        }
                    }
                }

                // max_tokens에 걸려 잘렸거나 content가 빈 경우는 거의 결정론적이라
                // 다시 불러도 똑같다. 재시도하지 않고 바로 포기한다.
                PostOutcome.Empty -> {
                    return null
                }

                PostOutcome.Retryable -> {}
            }
        }
        return null
    }

    internal fun visionBody(
        imageBase64: String,
        mediaType: String,
    ): Map<String, Any> =
        mapOf(
            "model" to properties.visionModel,
            "messages" to
                listOf(
                    mapOf(
                        "role" to "user",
                        "content" to
                            listOf(
                                mapOf("type" to "text", "text" to PROMPT),
                                mapOf(
                                    "type" to "image_url",
                                    "image_url" to mapOf("url" to "data:$mediaType;base64,$imageBase64"),
                                ),
                            ),
                    ),
                ),
            "response_format" to RESPONSE_FORMAT,
            // 안 보내면 잔액이 남았는데도 402가 난다.
            "max_tokens" to properties.visionMaxTokens,
        )

    private fun post(body: Map<String, Any>): PostOutcome =
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
            if (choice?.path("finish_reason")?.asString() == "length") {
                log.error { "관리비 인식이 max_tokens에 걸려 잘렸다 — 한도를 올려야 한다" }
                return PostOutcome.Empty
            }
            val content =
                choice
                    ?.path("message")
                    ?.path("content")
                    ?.asString()
                    ?.takeIf { it.isNotBlank() }
            if (content != null) PostOutcome.Content(content) else PostOutcome.Empty
        } catch (e: WebClientResponseException) {
            log.error(e) { "관리비 인식 호출 실패 (${e.statusCode})" }
            // 401·402·429는 같은 요청을 바로 다시 보내도 같은 답이 온다.
            if (e.statusCode.is4xxClientError) PostOutcome.Empty else PostOutcome.Retryable
        } catch (e: Exception) {
            log.error(e) { "관리비 인식 호출 실패" }
            PostOutcome.Retryable
        }

    internal fun parse(content: String): RecognizedBill {
        val root = JSON_MAPPER.readTree(content)
        val summary = root.path("summary")
        val items: Iterable<JsonNode> = root.path("items")
        val usages: Iterable<JsonNode> = root.path("usages")
        return RecognizedBill(
            year = root.path("year").asInt(),
            month = root.path("month").asInt(),
            dong = root.path("dong").asString(),
            ho = root.path("ho").asString(),
            areaM2 = root.path("areaM2").decimalValue(),
            items =
                items.map {
                    RecognizedItem(it.path("name").asString(), it.path("amount").decimalValue())
                },
            usages =
                usages.map {
                    RecognizedUsage(
                        it.path("name").asString(),
                        it.path("value").decimalValue(),
                        it.path("unit").asString(),
                    )
                },
            chargedAmount = summary.path("chargedAmount").decimalValue(),
            discountTotal = summary.path("discountTotal").decimalValue(),
            unpaidAmount = summary.path("unpaidAmount").decimalValue(),
            unpaidLateFee = summary.path("unpaidLateFee").decimalValue(),
            dueAmount = summary.path("dueAmount").decimalValue(),
            dueDate = summary.path("dueDate").asString(),
        )
    }

    companion object {
        private const val MAX_ATTEMPTS = 2

        /** 스레드 안전하고 상태가 없다. 파싱마다 새로 만들 이유가 없다. */
        private val JSON_MAPPER = JsonMapper.builder().build()

        private const val LOG_CONTENT_LIMIT = 500

        /**
         * **실측에서 16/16을 낸 프롬프트다. 문구를 다듬지 마라** — 이 문장들이 측정된 대상이다.
         */
        private val PROMPT =
            """
            이 사진은 아파트 관리비 납입영수증(입주자용) 종이 고지서를 찍은 것이다.

            표 구조:
            - 맨 위에 "YYYY 년 M 월 XXXX 동 XXXX 호 NN.N㎡"가 적혀 있다.
            - 그 아래 큰 상자는 **2단 표**다. 왼쪽 단과 오른쪽 단에 각각 [항목명] [금액]이 있다.
              왼쪽 단을 위에서 아래로 다 읽은 뒤, 오른쪽 단을 위에서 아래로 읽어라.
            - 오른쪽 바깥에 당월부과액 / 할인총계 / 미납액 / 미납연체료, 그리고 납기내 날짜와 금액이 있다.

            items — 금액이 적힌 모든 줄을 넣어라.
            - name은 항목명에서 **사용량 부분을 뺀 이름만** 넣어라. "전기 290kwh"는 name이 "전기",
              "수도 10.3 ㎥"는 "수도", "온수 4.3㎥"는 "온수", "난방 0.45"는 "난방"이다.
            - amount는 쉼표를 뺀 정수다. **"관리비차감"처럼 앞에 -가 붙은 값은 음수 그대로** 넣어라.
            - 금액 칸이 비어 있는 줄(예: "음식물 2.75")은 items에 넣지 마라. usages에만 넣는다.
            - 표에 없는 항목을 지어내지 마라. 달마다 있는 항목이 다르다(여름에는 난방이 없다).

            usages — 항목명 옆에 붙어 있는 사용량만 넣어라.
            - 예: 전기 290 kwh / 수도 10.3 ㎥ / 난방 0.45 / 온수 4.3 ㎥ / 음식물 2.75
            - unit은 보이는 그대로 넣되(kwh, ㎥), 단위 표기가 없으면 빈 문자열("")로 두어라.
            - 사용량이 적힌 항목이 없으면 빈 배열로 두어라.

            summary:
            - chargedAmount는 "당월부과액", dueAmount는 "납 기 내" 상자 안의 큰 금액이다.
            - discountTotal(할인총계) / unpaidAmount(미납액) / unpaidLateFee(미납연체료)는
              **칸이 비어 있으면 0이다. 값을 지어내지 마라.**
            - dueDate는 "납 기 내" 옆 날짜를 YYYY-MM-DD로 넣어라.

            읽을 수 없는 값을 추측해서 채우지 마라.
            """.trimIndent()

        private val SUMMARY_PROPERTIES =
            mapOf(
                "chargedAmount" to mapOf("type" to "integer"),
                "discountTotal" to mapOf("type" to "integer"),
                "unpaidAmount" to mapOf("type" to "integer"),
                "unpaidLateFee" to mapOf("type" to "integer"),
                "dueAmount" to mapOf("type" to "integer"),
                "dueDate" to mapOf("type" to "string"),
            )

        private val SCHEMA_PROPERTIES =
            mapOf(
                "year" to mapOf("type" to "integer"),
                "month" to mapOf("type" to "integer"),
                "dong" to mapOf("type" to "string"),
                "ho" to mapOf("type" to "string"),
                "areaM2" to mapOf("type" to "number"),
                "items" to
                    mapOf(
                        "type" to "array",
                        "items" to
                            mapOf(
                                "type" to "object",
                                "properties" to
                                    mapOf(
                                        "name" to mapOf("type" to "string"),
                                        "amount" to mapOf("type" to "integer"),
                                    ),
                                "required" to listOf("name", "amount"),
                                "additionalProperties" to false,
                            ),
                    ),
                "usages" to
                    mapOf(
                        "type" to "array",
                        "items" to
                            mapOf(
                                "type" to "object",
                                "properties" to
                                    mapOf(
                                        "name" to mapOf("type" to "string"),
                                        "value" to mapOf("type" to "number"),
                                        "unit" to mapOf("type" to "string"),
                                    ),
                                "required" to listOf("name", "value", "unit"),
                                "additionalProperties" to false,
                            ),
                    ),
                "summary" to
                    mapOf(
                        "type" to "object",
                        "properties" to SUMMARY_PROPERTIES,
                        "required" to SUMMARY_PROPERTIES.keys.toList(),
                        "additionalProperties" to false,
                    ),
            )

        private val RESPONSE_FORMAT =
            mapOf(
                "type" to "json_schema",
                "json_schema" to
                    mapOf(
                        "name" to "maintenance_bill",
                        "strict" to true,
                        "schema" to
                            mapOf(
                                "type" to "object",
                                "properties" to SCHEMA_PROPERTIES,
                                // strict: true라 properties에 있는 키가 required에 없으면
                                // 호출 자체가 거부된다. 손으로 나열하면 키를 더할 때 빠뜨린다.
                                "required" to SCHEMA_PROPERTIES.keys.toList(),
                                "additionalProperties" to false,
                            ),
                    ),
            )
    }
}
