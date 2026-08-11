package com.toy.backend.dispatch.llm

import com.toy.backend.dispatch.image.ImageSlice
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

data class RecognizedCell(
    val day: Int,
    /** 칸에 보이는 그대로. **빈 칸은 빈 문자열이다** — null로 두면 스키마가 strict를 못 건다. */
    val value: String,
)

data class RecognizedSlice(
    val hasNameColumn: Boolean,
    val rowIndex: Int,
    val rowCount: Int,
    val year: Int,
    val month: Int,
    /** 이 조각에서 보이는 날짜 헤더. **집계 컬럼을 날짜로 셌는지 검증**하는 데 쓴다. */
    val visibleDays: List<Int>,
    val cells: List<RecognizedCell>,
)

/**
 * 배차표 조각 하나를 읽는다. 실패는 null로 돌려주되 **한 번은 재시도한다** —
 * 실측에서 5회 중 1회 JSON 파싱에 실패했고, 사용자가 사진을 다시 올려야 하는
 * 대면 흐름이라 그 한 번은 서버가 삼킨다. 조각당 $0.017이다.
 */
class DispatchVisionClient(
    private val properties: DispatchVisionProperties,
    private val webClient: WebClient,
) {
    fun read(
        slice: ImageSlice,
        targetName: String?,
        knownRowIndex: Int?,
    ): RecognizedSlice? {
        val body = visionBody(slice, targetName, knownRowIndex)
        repeat(MAX_ATTEMPTS) { attempt ->
            when (val outcome = post(body)) {
                is PostOutcome.Content -> {
                    try {
                        return parse(outcome.text)
                    } catch (e: Exception) {
                        log.warn(e) {
                            "배차표 인식 응답 파싱 실패 (${attempt + 1}/$MAX_ATTEMPTS): " +
                                outcome.text.take(LOG_CONTENT_LIMIT)
                        }
                    }
                }

                // max_tokens에 걸려 잘렸거나 content가 빈 경우는 이미지·프롬프트·토큰 한도의
                // 거의 결정론적 결과라 다시 불러도 똑같다. 재시도하지 않고 바로 포기한다.
                PostOutcome.Empty -> {
                    return null
                }

                // 네트워크 등 일시적 실패만 재시도할 가치가 있다. 아무 것도 하지 않고 다음 시도로 넘어간다.
                PostOutcome.Retryable -> {}
            }
        }
        return null
    }

    internal fun visionBody(
        slice: ImageSlice,
        targetName: String?,
        knownRowIndex: Int?,
    ): Map<String, Any> =
        mapOf(
            "model" to properties.visionModel,
            "messages" to
                listOf(
                    mapOf(
                        "role" to "user",
                        "content" to
                            listOf(
                                mapOf("type" to "text", "text" to prompt(targetName, knownRowIndex)),
                                mapOf(
                                    "type" to "image_url",
                                    "image_url" to mapOf("url" to "data:image/png;base64,${slice.base64}"),
                                ),
                            ),
                    ),
                ),
            "response_format" to RESPONSE_FORMAT,
            // 안 보내면 잔액이 남았는데도 402가 난다(`OpenRouterProperties` 주석과 같은 함정).
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
                // 잘린 응답은 content가 남아 있어도 JSON이 완성되지 않는다. 결정론적이라
                // 다시 불러도 똑같으니 파싱 실패 경로로 흘려보내 재시도시키지 않는다.
                log.error { "배차표 인식이 max_tokens에 걸려 잘렸다 — 한도를 올려야 한다" }
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
            log.error(e) { "배차표 인식 호출 실패 (${e.statusCode})" }
            // 401·402·429는 같은 요청을 바로 다시 보내도 같은 답이 온다. 키가 틀렸거나
            // 잔액이 없는데 지체 없이 한 번 더 부르면 비용과 대기만 두 배가 된다.
            if (e.statusCode.is4xxClientError) PostOutcome.Empty else PostOutcome.Retryable
        } catch (e: Exception) {
            log.error(e) { "배차표 인식 호출 실패" }
            PostOutcome.Retryable
        }

    private fun parse(content: String): RecognizedSlice {
        val root = JSON_MAPPER.readTree(content)
        val visible: Iterable<JsonNode> = root.path("visibleDays")
        val cells: Iterable<JsonNode> = root.path("cells")
        return RecognizedSlice(
            hasNameColumn = root.path("hasNameColumn").asBoolean(),
            rowIndex = root.path("rowIndex").asInt(),
            rowCount = root.path("rowCount").asInt(),
            year = root.path("year").asInt(),
            month = root.path("month").asInt(),
            visibleDays = visible.map { it.asInt() },
            cells = cells.map { RecognizedCell(it.path("day").asInt(), it.path("value").asString()) },
        )
    }

    private fun prompt(
        targetName: String?,
        knownRowIndex: Int?,
    ): String {
        val rowInstruction =
            if (targetName != null) {
                """
                이 사진에는 성명 컬럼이 보인다. hasNameColumn을 true로 하라.
                '$targetName' 기사의 행을 찾아 그 행을 읽어라.
                rowIndex에는 그 행이 데이터 행 중 위에서 몇 번째인지 넣어라(맨 위 데이터 행이 0).
                """.trimIndent()
            } else {
                """
                이 사진은 표의 오른쪽 일부만 잘라낸 것이라 성명 컬럼이 보이지 않는다.
                hasNameColumn을 false로, rowIndex에는 ${knownRowIndex ?: 0}을 넣어라.
                데이터 행 중 위에서 ${(knownRowIndex ?: 0) + 1}번째 행을 읽어라.
                """.trimIndent()
            }

        return """
            이 사진은 월간 버스 배차표(엑셀)를 확대한 것이다.

            표 구조:
            - 위에서부터 [날짜 헤더 행] → [요일 행] → 기사별 데이터 행들.
            - 날짜 컬럼 사이에 '계'(주간 합계), '2주 합계', '합계', '총근무' 같은 집계 컬럼이
              끼어 있다. **이건 날짜가 아니다.** 이 컬럼들을 날짜로 세면 그 뒤가 전부 밀린다.

            $rowInstruction

            rowCount에는 이 표의 전체 데이터 행 수(기사 수)를 넣어라.
            year와 month에는 표 제목에서 읽은 연도와 월을 넣어라.

            visibleDays — 이 사진에서 보이는 날짜 헤더 숫자를 왼쪽부터 순서대로 나열하라.
            집계 컬럼은 빼라.

            cells — 지정한 행의 각 날짜 칸에 보이는 것을 그대로 value에 넣어라.

            **가장 중요한 규칙: 빈 칸은 빈 칸이다.**
            이 표는 근무일보다 빈 칸(휴무)이 더 많다. 한 행의 날짜 칸 중 절반 이상이 비어 있는
            것이 정상이다. 비어 있으면 value를 빈 문자열("")로 두어라. 숫자를 지어내지 마라.

            칸에 색(주황/초록/노랑)이 칠해져 있어도 글자가 없으면 빈 칸이다. 색칠 자체는 값이 아니다.

            값이 보이면 그 칸에서 수직으로 위로 올라가 날짜 헤더를 확인하라. 왼쪽부터 순서대로
            세다가 한 칸이라도 어긋나면 그 뒤가 전부 밀린다. 집계 컬럼은 폭이 좁고 배경이
            베이지색이니 셀 때 건너뛰어라.

            사진에서 잘려 보이지 않는 날짜는 cells에 넣지 마라. 추측해서 채우지 마라.
            """.trimIndent()
    }

    companion object {
        private const val MAX_ATTEMPTS = 2

        /** 스레드 안전하고 상태가 없다. 파싱마다 새로 만들 이유가 없다. */
        private val JSON_MAPPER = JsonMapper.builder().build()

        /** 파싱 실패 로그에 남길 content 최대 길이. 모델이 만든 임의 길이 텍스트를 통째로 남기지 않는다. */
        private const val LOG_CONTENT_LIMIT = 500

        private val RESPONSE_FORMAT =
            mapOf(
                "type" to "json_schema",
                "json_schema" to
                    mapOf(
                        "name" to "dispatch_row",
                        "strict" to true,
                        "schema" to
                            mapOf(
                                "type" to "object",
                                "properties" to
                                    mapOf(
                                        "hasNameColumn" to mapOf("type" to "boolean"),
                                        "rowIndex" to mapOf("type" to "integer"),
                                        "rowCount" to mapOf("type" to "integer"),
                                        "year" to mapOf("type" to "integer"),
                                        "month" to mapOf("type" to "integer"),
                                        "visibleDays" to
                                            mapOf(
                                                "type" to "array",
                                                "items" to mapOf("type" to "integer"),
                                            ),
                                        "cells" to
                                            mapOf(
                                                "type" to "array",
                                                "items" to
                                                    mapOf(
                                                        "type" to "object",
                                                        "properties" to
                                                            mapOf(
                                                                "day" to mapOf("type" to "integer"),
                                                                "value" to mapOf("type" to "string"),
                                                            ),
                                                        "required" to listOf("day", "value"),
                                                        "additionalProperties" to false,
                                                    ),
                                            ),
                                    ),
                                // strict: true라 properties에 있는 키가 required에 없으면 호출 자체가 거부된다.
                                "required" to
                                    listOf(
                                        "hasNameColumn",
                                        "rowIndex",
                                        "rowCount",
                                        "year",
                                        "month",
                                        "visibleDays",
                                        "cells",
                                    ),
                                "additionalProperties" to false,
                            ),
                    ),
            )
    }
}
