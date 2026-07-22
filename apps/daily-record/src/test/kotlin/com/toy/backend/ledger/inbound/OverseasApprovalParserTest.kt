package com.toy.backend.ledger.inbound

import com.toy.backend.ledger.entries.EntrySource
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.LocalDateTime

// 실제 수신 문자 샘플 (2026-07-19 수집)
private val overseasText =
    """
    [Web발신]
    [현대카드] 해외승인
    문*민님
    07/14 23:15
    JPY 1,000.00
    SUICAMOBILEPAYMENT
    """.trimIndent()

class OverseasApprovalParserTest :
    BehaviorSpec({
        val parser = OverseasApprovalParser()
        val receivedAt = LocalDateTime.of(2026, 7, 14, 23, 20)

        Given("해외 승인 문자") {
            When("supports") {
                Then("처리 가능") { parser.supports(overseasText).shouldBeTrue() }
            }
            When("parse") {
                val result = parser.parse(overseasText, receivedAt)
                Then("통화·금액 그대로, 가맹점은 금액 다음 줄") {
                    result.kind shouldBe ParsedKind.APPROVAL
                    result.currency shouldBe "JPY"
                    result.amount shouldBe BigDecimal("1000.00")
                    result.merchant shouldBe "SUICAMOBILEPAYMENT"
                    result.occurredAt shouldBe LocalDateTime.of(2026, 7, 14, 23, 15)
                    result.source shouldBe EntrySource.SMS
                }
            }
        }

        Given("해외승인 취소 문자") {
            When("parse") {
                val result = parser.parse(overseasText.replace("해외승인", "해외승인 취소"), receivedAt)
                Then("CANCEL로 판별") { result.kind shouldBe ParsedKind.CANCEL }
            }
        }

        Given("국내 카드 문자") {
            When("supports") {
                Then("처리 불가 (해외승인 문구 없음)") {
                    parser.supports("대한항공카드 승인\n18,920원 일시불\n07/14 07:38").shouldBeFalse()
                }
            }
        }
    })
