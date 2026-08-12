package com.toy.backend.diet.llm

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

/**
 * **`max_tokens`가 빠지면 잔액이 남았는데도 402가 난다.** OpenRouter가 호출 전에 「최대로 들 수
 * 있는 비용」만큼 잔액을 선점하는데, 그 값이 없으면 모델의 최대 출력을 쓰기 때문이다
 * (`google/gemini-2.5-flash`는 65,535토큰 × $0.0000025 = **$0.1638**).
 *
 * 그 실패는 실기동에서만 드러나므로 본문 구성을 여기서 고정한다.
 */
class OpenRouterClientTest :
    BehaviorSpec({
        fun clientWith(
            properties: OpenRouterProperties = OpenRouterProperties(apiKey = "sk-test"),
            responseJson: String = """{"choices":[{"finish_reason":"stop","message":{"content":"안녕하세요"}}]}""",
        ): OpenRouterClient {
            val exchange =
                ExchangeFunction {
                    Mono.just(
                        ClientResponse
                            .create(HttpStatus.OK)
                            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                            .body(responseJson)
                            .build(),
                    )
                }
            return OpenRouterClient(
                properties,
                WebClient
                    .builder()
                    .baseUrl("http://localhost")
                    .exchangeFunction(exchange)
                    .build(),
            )
        }

        Given("사진 인식 요청 본문") {
            val client = clientWith()
            val body = client.visionBody("QUJD", "image/jpeg")

            Then("max_tokens를 반드시 싣는다 — 없으면 잔액이 남아도 402가 난다") {
                body["max_tokens"] shouldBe 4000
            }

            Then("설정값을 그대로 쓴다") {
                clientWith(OpenRouterProperties(apiKey = "k", visionMaxTokens = 777))
                    .visionBody("QUJD", "image/jpeg")["max_tokens"] shouldBe 777
            }

            Then("기존 필드는 그대로다") {
                body["model"] shouldBe "google/gemini-2.5-flash"
                body["response_format"].shouldNotBeNull()
            }
        }

        Given("문장 생성 요청 본문") {
            val client = clientWith()
            val body = client.chatBody("시스템", listOf(ChatTurn("user", "사용자")))

            Then("max_tokens를 싣는다") {
                body["max_tokens"] shouldBe 2000
            }

            Then("설정값을 그대로 쓴다") {
                clientWith(OpenRouterProperties(apiKey = "k", textMaxTokens = 123))
                    .chatBody("s", listOf(ChatTurn("user", "u")))["max_tokens"] shouldBe 123
            }

            Then("사진 인식과 다른 모델·한도를 쓴다 — 문장 생성이 더 싸고 짧다") {
                body["model"] shouldNotBe client.visionBody("QUJD", "image/jpeg")["model"]
                body["max_tokens"] shouldNotBe client.visionBody("QUJD", "image/jpeg")["max_tokens"]
            }

            // 히스토리가 뒤섞이면 모델이 누가 무슨 말을 했는지 잃는다.
            Then("system이 맨 앞이고 턴이 준 순서 그대로 뒤에 붙는다") {
                val many =
                    client.chatBody(
                        "시스템",
                        listOf(
                            ChatTurn("user", "데이터"),
                            ChatTurn("assistant", "총평"),
                            ChatTurn("user", "질문"),
                        ),
                    )

                @Suppress("UNCHECKED_CAST")
                val messages = many["messages"] as List<Map<String, String>>
                messages.map { it["role"] } shouldBe listOf("system", "user", "assistant", "user")
                messages.map { it["content"] } shouldBe listOf("시스템", "데이터", "총평", "질문")
            }
        }

        Given("정상 응답이면") {
            Then("content를 다듬어 돌려준다") {
                clientWith(
                    responseJson = """{"choices":[{"finish_reason":"stop","message":{"content":"  좋아요  "}}]}""",
                ).generateText("s", "u") shouldBe "좋아요"
            }
        }

        // Gemini 2.5 계열은 생각(reasoning) 토큰이 `max_tokens`에 함께 잡혀서, 한도를 다 쓰면
        // content가 빈 채로 온다. 한도를 너무 줄였을 때 이렇게 조용히 실패한다.
        Given("한도에 걸려 잘린 응답이면") {
            Then("null — 잘린 JSON을 억지로 쓰지 않는다") {
                clientWith(
                    responseJson = """{"choices":[{"finish_reason":"length","message":{"content":""}}]}""",
                ).generateText("s", "u") shouldBe null
            }

            Then("사진 인식도 null") {
                clientWith(
                    responseJson = """{"choices":[{"finish_reason":"length","message":{"content":"{\"items\":[{\"name\":\"제육"}}]}""",
                ).recognizeFoods("QUJD", "image/jpeg") shouldBe null
            }
        }

        Given("choices가 비어 있으면") {
            Then("null — 호출자가 LLM 없이 진행한다") {
                clientWith(responseJson = """{"choices":[]}""").generateText("s", "u") shouldBe null
            }
        }
    })
