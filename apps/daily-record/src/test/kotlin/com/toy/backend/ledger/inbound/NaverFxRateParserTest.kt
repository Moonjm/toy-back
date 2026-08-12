package com.toy.backend.ledger.inbound

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal

/**
 * 네이버 계산기 응답은 문서화된 API가 아니라 **형식 변화가 이 연동의 가장 큰 위험이다.**
 * 실제로 받아 본 응답을 그대로 고정해 둔다.
 */
class NaverFxRateParserTest :
    BehaviorSpec({
        val mapper = JsonMapper.builder().build()

        fun parse(
            json: String,
            units: Int = 100,
        ) = NaverFxRateParser.parse(mapper.readTree(json), units)

        Given("실제 응답(JPY, 100단위)") {
            val body =
                """
                { "pkid" : 141, "count" : 1, "country" :
                  [{ "value" : "100", "subValue" : "100 엔", "currencyUnit" : "엔" },
                   { "value" : "910.19", "subValue" : "910.19 원", "currencyUnit" : "원" }],
                  "calculatorMessage" : "" }
                """.trimIndent()

            Then("물어본 단위로 나눠 1단위당 환율을 낸다") {
                parse(body) shouldBe BigDecimal("9.1019")
            }
        }

        // 원화 환산액에 천 단위 쉼표가 들어온다. 안 떼면 숫자로 못 읽어 통째로 null이 된다.
        Given("천 단위 쉼표가 든 응답(USD)") {
            val body =
                """
                { "country" : [{ "value" : "100" }, { "value" : "142,820" }] }
                """.trimIndent()

            Then("쉼표를 떼고 읽는다") {
                parse(body) shouldBe BigDecimal("1428.2")
            }
        }

        // u2=1로 물으면 소수 둘째 자리에서 잘려 VND가 0.05(실제 0.0544)가 된다 — 8% 오차다.
        Given("저액 통화(VND, 100단위)") {
            val body = """{ "country" : [{ "value" : "100" }, { "value" : "5.44" }] }"""

            Then("100으로 나눠도 자릿수가 살아 있다") {
                parse(body) shouldBe BigDecimal("0.0544")
            }
        }

        // 응답이 소수 둘째 자리까지라 몇 단위를 물어보느냐가 정밀도를 정한다. **이 상수를 줄이면
        // 어디서도 예외가 안 나고 값만 조용히 틀어진다.**
        Given("조회 단위") {
            Then("100 이상이어야 한다 — 1로 물으면 VND가 0.05로 와서 실제 0.0544 대비 8% 틀린다") {
                (NaverFxRateSource.UNITS >= 100) shouldBe true
            }

            Then("1단위로 물었다면 저액 통화가 얼마나 뭉개지는지") {
                // 같은 환율을 1단위로 물었을 때 받게 될 응답(`0.05`)과 100단위(`5.44`)의 차이다.
                val coarse = parse("""{ "country" : [{ "value" : "1" }, { "value" : "0.05" }] }""", units = 1)!!
                val fine = parse("""{ "country" : [{ "value" : "100" }, { "value" : "5.44" }] }""")!!
                ((fine - coarse).abs() / fine > BigDecimal("0.05")) shouldBe true
            }
        }

        Given("모르는 통화라 빈 객체가 오면") {
            Then("null — 호출자가 Frankfurter로 넘어간다") {
                parse("{}") shouldBe null
            }
        }

        // 아래 셋은 「형식이 바뀌었다」의 신호다. 조용히 엉뚱한 값을 내는 것보다 null이 낫다.
        Given("country 배열의 크기가 둘이 아니면") {
            Then("null") {
                parse("""{ "country" : [{ "value" : "100" }] }""") shouldBe null
                parse("""{ "country" : [{ "value" : "1" }, { "value" : "2" }, { "value" : "3" }] }""") shouldBe null
            }
        }

        Given("country가 배열이 아니면") {
            Then("null") {
                parse("""{ "country" : "1,428" }""") shouldBe null
            }
        }

        Given("환산액이 숫자가 아니면") {
            Then("null") {
                parse("""{ "country" : [{ "value" : "100" }, { "value" : "-" }] }""") shouldBe null
                parse("""{ "country" : [{ "value" : "100" }, { }] }""") shouldBe null
            }
        }

        // 0이나 음수는 환율일 수 없다 — 엉뚱한 칸을 읽었다는 뜻이다.
        Given("환산액이 0 이하면") {
            Then("null") {
                parse("""{ "country" : [{ "value" : "100" }, { "value" : "0" }] }""") shouldBe null
                parse("""{ "country" : [{ "value" : "100" }, { "value" : "-5" }] }""") shouldBe null
            }
        }
    })
