package com.toy.backend.maintenance.llm

import com.toy.backend.vision.VisionProperties
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.springframework.web.reactive.function.client.WebClient
import io.kotest.matchers.string.shouldContain as stringShouldContain

/**
 * **`max_tokens`를 크게 잡아야 한다.** `gemini-3.7-flash`는 reasoning 토큰을 이 한도에
 * 함께 쓴다(실측 completion 1,773~2,333). 식단용 기본값(4,000)으로 두면 `content`가
 * 빈 채로 온다. 아예 안 보내면 OpenRouter가 모델 최대 출력만큼 잔액을 선점해 402가 난다.
 */
class MaintenanceVisionClientTest :
    BehaviorSpec({
        val webClient = mockk<WebClient>()

        fun client(properties: VisionProperties = VisionProperties(apiKey = "sk-test")) = MaintenanceVisionClient(properties, webClient)

        Given("요청 본문") {
            val body = client().visionBody("QUJD", "image/jpeg")

            Then("설정된 모델을 쓴다") {
                body["model"] shouldBe "google/gemini-3.7-flash"
            }

            Then("max_tokens를 보낸다") {
                body["max_tokens"] shouldBe 30000
            }

            Then("이미지를 보낸 media type의 data URL로 싣는다") {
                val messages = body["messages"] as List<*>
                val content = (messages.first() as Map<*, *>)["content"] as List<*>
                val image = content.last() as Map<*, *>
                val url = (image["image_url"] as Map<*, *>)["url"]
                url shouldBe "data:image/jpeg;base64,QUJD"
            }

            Then("프롬프트가 음수 항목을 명시한다") {
                val messages = body["messages"] as List<*>
                val content = (messages.first() as Map<*, *>)["content"] as List<*>
                val text = (content.first() as Map<*, *>)["text"] as String
                text stringShouldContain "관리비차감"
                text stringShouldContain "음수"
            }

            Then("strict 스키마의 required가 properties를 모두 덮는다") {
                // strict: true라 properties에 있는 키가 required에 없으면 호출 자체가 거부된다.
                val format = body["response_format"] as Map<*, *>
                val schema = (format["json_schema"] as Map<*, *>)["schema"] as Map<*, *>
                val properties = (schema["properties"] as Map<*, *>).keys
                val required = schema["required"] as List<*>
                required.toSet() shouldBe properties
            }
        }

        Given("정상 응답 JSON") {
            val json =
                """
                {"year":2026,"month":3,"dong":"5103","ho":"1404","areaM2":98.8,
                 "items":[{"name":"일반관리비","amount":34700},{"name":"관리비차감","amount":-13790}],
                 "usages":[{"name":"전기","value":261,"unit":"kwh"}],
                 "summary":{"chargedAmount":238370,"discountTotal":0,"unpaidAmount":0,
                            "unpaidLateFee":0,"dueAmount":238370,"dueDate":"2026-04-30"}}
                """.trimIndent()

            When("파싱하면") {
                val parsed = client().parse(json)

                Then("음수 항목이 음수로 남는다") {
                    parsed.items.map { it.name to it.amount.toInt() } shouldContain ("관리비차감" to -13790)
                }

                Then("사용량과 요약을 읽는다") {
                    parsed.usages.single().name shouldBe "전기"
                    parsed.chargedAmount.toInt() shouldBe 238370
                    parsed.dueDate shouldBe "2026-04-30"
                }
            }
        }
    })
