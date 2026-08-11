package com.toy.backend.dispatch.llm

import com.toy.backend.dispatch.image.ImageSlice
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicInteger

/**
 * **`max_tokens`를 크게 잡아야 한다.** `gemini-3.6-flash`는 reasoning 토큰을 1,300~3,000
 * 쓰는데 이 값이 `max_tokens`에 함께 잡힌다. 식단용 기본값 4,000으로 두면 `content`가
 * 빈 채로 온다(실측에서 `2.5-pro`로 재현됐다).
 *
 * 그리고 **재시도 1회**가 필요하다 — 실측에서 5회 중 1회 JSON 파싱에 실패했다.
 * 사용자가 사진을 다시 올려야 하는 대면 흐름이라 한 번은 서버가 삼킨다.
 */
class DispatchVisionClientTest :
    BehaviorSpec({
        val slice = ImageSlice(index = 0, base64 = "AAAA", xFrom = 0, xTo = 100)

        val validJson =
            """
            {"hasNameColumn":true,"rowIndex":2,"rowCount":13,"year":2026,"month":8,
             "visibleDays":[1,2,3],
             "cells":[{"day":1,"value":"1"},{"day":2,"value":""},{"day":3,"value":"*97"}]}
            """.trimIndent()

        fun clientWith(
            vararg responseBodies: String,
            properties: DispatchVisionProperties = DispatchVisionProperties(apiKey = "sk-test"),
        ): Pair<DispatchVisionClient, AtomicInteger> {
            val calls = AtomicInteger(0)
            val exchange =
                ExchangeFunction {
                    val body = responseBodies[minOf(calls.getAndIncrement(), responseBodies.size - 1)]
                    Mono.just(
                        ClientResponse
                            .create(HttpStatus.OK)
                            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                            .body(body)
                            .build(),
                    )
                }
            val webClient =
                WebClient
                    .builder()
                    .baseUrl("http://localhost")
                    .exchangeFunction(exchange)
                    .build()
            return DispatchVisionClient(properties, webClient) to calls
        }

        fun wrap(content: String) = """{"choices":[{"finish_reason":"stop","message":{"content":${jsonQuote(content)}}}]}"""

        Given("요청 본문") {
            val properties = DispatchVisionProperties(apiKey = "sk-test")
            val body = DispatchVisionClient(properties, WebClient.builder().build()).visionBody(slice, "홍길동", null)

            Then("모델이 배차 전용 설정을 따른다") {
                body["model"] shouldBe "google/gemini-3.6-flash"
            }

            Then("max_tokens가 들어간다") {
                body["max_tokens"] shouldBe 30000
            }

            Then("strict json_schema로 고정된다") {
                val format = body["response_format"] as Map<*, *>
                format["type"] shouldBe "json_schema"
                ((format["json_schema"] as Map<*, *>)["strict"]) shouldBe true
            }

            Then("집계 컬럼을 제외하라고 지시한다") {
                // 이걸 빼면 '계'를 날짜로 세어 그 뒤가 전부 밀린다.
                promptOf(body) shouldContain "계"
                promptOf(body) shouldContain "집계"
            }

            Then("빈 칸을 지어내지 말라고 지시한다") {
                promptOf(body) shouldContain "빈"
            }
        }

        Given("이름을 준 경우") {
            val body =
                DispatchVisionClient(DispatchVisionProperties(apiKey = "sk-test"), WebClient.builder().build())
                    .visionBody(slice, "홍길동", null)

            Then("이름으로 행을 찾으라고 지시한다") {
                promptOf(body) shouldContain "홍길동"
            }
        }

        Given("이름 없이 행 위치만 준 경우") {
            val body =
                DispatchVisionClient(DispatchVisionProperties(apiKey = "sk-test"), WebClient.builder().build())
                    .visionBody(slice, null, 2)

            Then("위에서 3번째 행을 읽으라고 지시한다") {
                // rowIndex는 0-based, 프롬프트는 사람이 세는 1-based로 준다.
                promptOf(body) shouldContain "3번째"
            }
        }

        Given("정상 응답") {
            val (client, calls) = clientWith(wrap(validJson))
            val result = client.read(slice, "홍길동", null)

            Then("파싱된다") {
                result?.rowIndex shouldBe 2
                result?.rowCount shouldBe 13
                result?.visibleDays shouldBe listOf(1, 2, 3)
                result?.cells?.size shouldBe 3
                result?.cells?.get(1)?.value shouldBe ""
            }

            Then("한 번만 부른다") {
                calls.get() shouldBe 1
            }
        }

        Given("첫 응답이 깨진 JSON인 경우") {
            val (client, calls) = clientWith(wrap("""{"hasNameColumn":"""), wrap(validJson))
            val result = client.read(slice, "홍길동", null)

            Then("한 번 재시도해 성공한다") {
                calls.get() shouldBe 2
                result?.rowIndex shouldBe 2
            }
        }

        Given("두 번 다 실패하는 경우") {
            val (client, calls) = clientWith(wrap("""{"broken"""))
            val result = client.read(slice, "홍길동", null)

            Then("두 번까지만 부르고 null을 준다") {
                calls.get() shouldBe 2
                result shouldBe null
            }
        }

        Given("content가 빈 응답") {
            val (client, _) = clientWith("""{"choices":[{"finish_reason":"length","message":{"content":""}}]}""")

            Then("null을 준다") {
                client.read(slice, "홍길동", null) shouldBe null
            }
        }
    })

private fun jsonQuote(raw: String): String =
    tools.jackson.databind.json.JsonMapper
        .builder()
        .build()
        .writeValueAsString(raw)

@Suppress("UNCHECKED_CAST")
private fun promptOf(body: Map<String, Any>): String {
    val messages = body["messages"] as List<Map<String, Any>>
    val content = messages[0]["content"] as List<Map<String, Any>>
    return content.first { it["type"] == "text" }["text"] as String
}
