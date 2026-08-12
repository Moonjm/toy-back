package com.toy.backend.dispatch.llm

import com.toy.backend.dispatch.image.ImageSlice
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
        val slice = ImageSlice(index = 0, base64 = "AAAA")

        val validJson =
            """
            {"hasNameColumn":true,"targetFound":true,"rowIndex":2,"rowCount":13,"year":2026,"month":8,
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

        /** 같은 상태 코드를 계속 돌려주는 클라이언트. 호출 횟수만 세면 된다. */
        fun clientWithStatus(status: HttpStatus): Pair<DispatchVisionClient, AtomicInteger> {
            val calls = AtomicInteger(0)
            val exchange =
                ExchangeFunction {
                    calls.incrementAndGet()
                    Mono.just(
                        ClientResponse
                            .create(status)
                            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                            .body("""{"error":{"message":"nope"}}""")
                            .build(),
                    )
                }
            val webClient =
                WebClient
                    .builder()
                    .baseUrl("http://localhost")
                    .exchangeFunction(exchange)
                    .build()
            return DispatchVisionClient(DispatchVisionProperties(apiKey = "sk-test"), webClient) to calls
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

            Then("targetFound가 properties와 required에 모두 들어간다") {
                // strict: true라 properties에 있는 키가 required에 없으면 호출 자체가 거부된다.
                val schema =
                    ((body["response_format"] as Map<*, *>)["json_schema"] as Map<*, *>)["schema"] as Map<*, *>
                (schema["properties"] as Map<*, *>).containsKey("targetFound") shouldBe true
                (schema["required"] as List<*>).contains("targetFound") shouldBe true
            }

            Then("집계 컬럼을 제외하라고 지시한다") {
                // 이걸 빼면 '계'를 날짜로 세어 그 뒤가 전부 밀린다.
                promptOf(body) shouldContain "계"
                promptOf(body) shouldContain "집계"
            }

            Then("빈 칸을 지어내지 말라고 지시한다") {
                promptOf(body) shouldContain "빈"
            }

            // 스키마가 year·month를 required 정수로 두므로 모델은 **무엇이든 채워야 한다.**
            // 무엇을 채울지 정해 주지 않으면 제목이 잘린 사진에서 그럴싸한 연월을 지어내고,
            // 그 값이 곧 기준 연월이 되어 「최근 줄 위치를 빌려 쓰고 경고한다」는 갈래를
            // 통째로 건너뛴다 — 다른 달의 행 위치로 다른 기사의 근무를 읽는다.
            Then("연월을 못 읽으면 0을 넣으라고 지시한다") {
                promptOf(body) shouldContain "읽을 수 없으면 year와 month에 0을 넣어라"
            }
        }

        Given("이름을 준 경우") {
            val body =
                DispatchVisionClient(DispatchVisionProperties(apiKey = "sk-test"), WebClient.builder().build())
                    .visionBody(slice, "홍길동", null)

            Then("이름으로 행을 찾으라고 지시한다") {
                promptOf(body) shouldContain "홍길동"
            }

            // hasNameColumn은 「보이는가」를 묻는 값이다. 프롬프트가 「보인다」고 단정하면
            // 잘린 사진에도 모델이 true로 답하고 아무 행이나 읽어, 저장된 행 위치를 쓰는 갈래도
            // ROSTER_NOT_FOUND 거부도 건너뛴다 — 다른 기사의 근무가 그대로 들어온다.
            Then("성명 컬럼이 보이는지 먼저 판단하라고 지시한다") {
                promptOf(body) shouldContain "판단하라"
            }

            Then("보인다고 단정하지 않는다") {
                promptOf(body) shouldNotContain "성명 컬럼이 보인다"
            }

            Then("이름을 못 찾으면 아무 행이나 읽지 말라고 지시한다") {
                promptOf(body) shouldContain "아무 행이나 읽어서 채우지 마라"
            }

            // 「컬럼이 보이는가」와 「그 안에서 찾았는가」는 다른 질문이다. 못 찾았다는 것을
            // 표현할 자리가 없으면 모델은 rowIndex에 아무 값이나 채우고, 그 값이 기준으로
            // 저장돼 이후 잘린 사진이 전부 다른 기사의 근무를 읽는다.
            Then("찾았는지를 targetFound로 따로 답하라고 지시한다") {
                promptOf(body) shouldContain "targetFound"
            }

            Then("비슷한 다른 이름을 대신 고르지 말라고 지시한다") {
                promptOf(body) shouldContain "비슷한 다른 이름을 대신 고르지 마라"
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
                result?.targetFound shouldBe true
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
            val (client, calls) = clientWith("""{"choices":[{"finish_reason":"length","message":{"content":""}}]}""")
            val result = client.read(slice, "홍길동", null)

            Then("null을 준다") {
                result shouldBe null
            }

            Then("재시도하지 않는다") {
                // max_tokens에 걸려 잘린 응답은 다시 불러도 똑같다. 재시도는 비용만 두 배로 든다.
                calls.get() shouldBe 1
            }
        }

        Given("잘렸지만 content가 남아 있는 응답") {
            // JSON이 완성되지 않아 파싱에 실패한다. 예전에는 이걸 파싱 실패로 보고 재시도했다.
            val truncated = """{"hasNameColumn":true,"rowIndex":2,"rowCou"""
            val (client, calls) =
                clientWith("""{"choices":[{"finish_reason":"length","message":{"content":${jsonQuote(truncated)}}}]}""")
            val result = client.read(slice, "홍길동", null)

            Then("null을 준다") {
                result shouldBe null
            }

            Then("재시도하지 않는다 — 잘림은 결정론적이라 다시 불러도 같다") {
                calls.get() shouldBe 1
            }
        }

        Given("키가 틀려 401이 오는 경우") {
            val (client, calls) = clientWithStatus(HttpStatus.UNAUTHORIZED)
            val result = client.read(slice, "홍길동", null)

            Then("null을 준다") {
                result shouldBe null
            }

            Then("재시도하지 않는다 — 같은 요청은 같은 4xx를 받는다") {
                calls.get() shouldBe 1
            }
        }

        Given("잔액이 없어 402가 오는 경우") {
            val (client, calls) = clientWithStatus(HttpStatus.PAYMENT_REQUIRED)
            client.read(slice, "홍길동", null)

            Then("재시도하지 않는다") {
                calls.get() shouldBe 1
            }
        }

        Given("서버 쪽 5xx가 오는 경우") {
            val (client, calls) = clientWithStatus(HttpStatus.BAD_GATEWAY)
            client.read(slice, "홍길동", null)

            Then("일시적 실패로 보고 한 번 재시도한다") {
                calls.get() shouldBe 2
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
