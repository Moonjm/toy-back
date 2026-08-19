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

/**
 * **카드 상품명에 「카드」라는 글자가 없는 형식.** 2026-08-19에 세 건(`ledger_inbound_messages`
 * 115·116·117)이 이 형태로 `PARSE_FAILED`가 됐다 — 발급사(`현대`)와 상품명(`대한항공030`)이
 * 갈라져 있어 어디에도 「카드」가 없다.
 */
private val approvalTextWithoutCardWord =
    """
    [Web발신]
    현대 대한항공030 승인
    문*민
    18,000원 일시불
    08/19 19:12
    릴리카롱
    누적1,545,023원
    """.trimIndent()

/**
 * 해외승인은 `OverseasApprovalParser`(`@Order(20)`) 몫이다. **이 파서가 `@Order(10)`으로 먼저
 * 물어보므로 여기서 가로채면 안 된다** — 통화·환율 처리가 통째로 빠진다.
 */
private val overseasText =
    """
    [Web발신]
    [현대카드] 해외승인
    문*민님
    07/14 23:15
    JPY 1,000.00
    SUICAMOBILEPAYMENT
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

        // 「카드」를 리터럴로 요구하던 정규식이 이 형식을 통째로 놓쳤다.
        Given("상품명에 「카드」가 없는 승인 문자") {
            When("supports") {
                Then("처리 가능") { parser.supports(approvalTextWithoutCardWord).shouldBeTrue() }
            }
            When("parse") {
                val result = parser.parse(approvalTextWithoutCardWord, LocalDateTime.of(2026, 8, 19, 19, 12))
                Then("금액·일시·가맹점 추출") {
                    result.kind shouldBe ParsedKind.APPROVAL
                    result.amount shouldBe BigDecimal("18000")
                    result.merchant shouldBe "릴리카롱"
                    result.occurredAt shouldBe LocalDateTime.of(2026, 8, 19, 19, 12)
                }
            }
            When("취소 문자로 오면") {
                val result =
                    parser.parse(
                        approvalTextWithoutCardWord.replace("승인", "취소"),
                        LocalDateTime.of(2026, 8, 19, 19, 12),
                    )
                Then("CANCEL로 판별") { result.kind shouldBe ParsedKind.CANCEL }
            }
        }

        // 이 파서가 먼저 물어보므로, 「카드」 요구를 뺀 뒤에도 해외승인을 넘겨야 한다.
        Given("해외승인 문자") {
            When("supports") {
                Then("처리 불가 — OverseasApprovalParser 몫이다") {
                    parser.supports(overseasText).shouldBeFalse()
                }
            }
        }

        // **거절 문자를 지출로 저장하면 안 된다.** 「승인」을 접미사로 품은 낱말이 걸리면
        // 금액·일시 줄이 정상이라 그대로 승인 건이 된다 — 금액이 틀리는 쪽이라 조용히 새어도
        // 알아채기 어렵다.
        Given("「미승인」으로 끝나는 거절 문자") {
            val declined = approvalTextWithoutCardWord.replace("030 승인", "030 미승인")
            When("supports") {
                Then("처리 불가") { parser.supports(declined).shouldBeFalse() }
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
