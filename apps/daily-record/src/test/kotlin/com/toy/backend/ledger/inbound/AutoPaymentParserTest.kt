package com.toy.backend.ledger.inbound

import com.toy.backend.ledger.entries.EntrySource
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.LocalDateTime

// 실제 수신 문자 (2026-07-23 수집) — 일시 없이 한 줄로 온다
private val autoPayText =
    """
    [Web발신]
    [현대카드] 자동납부 승인 문*민님 SK브로드밴드 34,100원
    """.trimIndent()

// 일반 승인 문자 — CardApprovalParser 몫이므로 이 파서는 거른다
private val cardApprovalText =
    """
    [Web발신]
    대한항공카드 승인
    문*민
    18,920원 일시불
    07/14 07:38
    제주특별자치도개발
    누적438,919원
    """.trimIndent()

class AutoPaymentParserTest :
    BehaviorSpec({
        val parser = AutoPaymentParser()
        val receivedAt = LocalDateTime.of(2026, 7, 23, 10, 30)

        Given("자동납부 승인 문자") {
            When("supports") {
                Then("처리 가능") { parser.supports(autoPayText).shouldBeTrue() }
            }
            When("parse") {
                val result = parser.parse(autoPayText, receivedAt)
                Then("금액·가맹점 추출, 일시가 없으므로 수신 시점을 발생 시각으로 쓴다") {
                    result.kind shouldBe ParsedKind.APPROVAL
                    result.amount shouldBe BigDecimal("34100")
                    result.currency shouldBe "KRW"
                    result.merchant shouldBe "SK브로드밴드"
                    result.occurredAt shouldBe receivedAt
                    result.source shouldBe EntrySource.SMS
                }
            }
        }

        Given("일반 카드 승인 문자") {
            When("supports") {
                Then("처리 불가 — 자동납부 문구가 없다") { parser.supports(cardApprovalText).shouldBeFalse() }
            }
        }
    })
