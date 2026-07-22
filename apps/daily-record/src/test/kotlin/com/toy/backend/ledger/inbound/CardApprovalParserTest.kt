package com.toy.backend.ledger.inbound

import com.toy.backend.ledger.entries.EntrySource
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.LocalDateTime

// 실제 수신 문자 샘플 (2026-07-19 수집)
private val approvalText =
    """
    [Web발신]
    대한항공카드 승인
    문*민
    18,920원 일시불
    07/14 07:38
    제주특별자치도개발
    누적438,919원
    """.trimIndent()

private val cancelText =
    """
    [Web발신]
    대한항공카드 취소
    문*민
    18,920원 일시불
    07/14 07:39
    제주특별자치도개발
    누적419,999원
    """.trimIndent()

class CardApprovalParserTest :
    BehaviorSpec({
        val parser = CardApprovalParser()
        val receivedAt = LocalDateTime.of(2026, 7, 14, 8, 0)

        Given("국내 카드 승인 문자") {
            When("supports") {
                Then("처리 가능") { parser.supports(approvalText).shouldBeTrue() }
            }
            When("parse") {
                val result = parser.parse(approvalText, receivedAt)
                Then("금액·일시·가맹점 추출") {
                    result.kind shouldBe ParsedKind.APPROVAL
                    result.amount shouldBe BigDecimal("18920")
                    result.currency shouldBe "KRW"
                    result.merchant shouldBe "제주특별자치도개발"
                    result.occurredAt shouldBe LocalDateTime.of(2026, 7, 14, 7, 38)
                    result.source shouldBe EntrySource.SMS
                }
            }
        }

        Given("국내 카드 취소 문자") {
            When("parse") {
                val result = parser.parse(cancelText, receivedAt)
                Then("CANCEL로 판별") {
                    result.kind shouldBe ParsedKind.CANCEL
                    result.amount shouldBe BigDecimal("18920")
                    result.merchant shouldBe "제주특별자치도개발"
                }
            }
        }

        Given("연도 경계") {
            When("1월 1일에 12/31 거래 문자를 수신하면") {
                val newYearReceived = LocalDateTime.of(2027, 1, 1, 0, 10)
                val text = approvalText.replace("07/14 07:38", "12/31 23:50")
                val result = parser.parse(text, newYearReceived)
                Then("전년도로 보정") {
                    result.occurredAt shouldBe LocalDateTime.of(2026, 12, 31, 23, 50)
                }
            }
        }

        Given("무관한 텍스트") {
            When("supports") {
                Then("처리 불가") {
                    parser.supports("안녕하세요 광고 문자입니다").shouldBeFalse()
                }
            }
        }
    })
