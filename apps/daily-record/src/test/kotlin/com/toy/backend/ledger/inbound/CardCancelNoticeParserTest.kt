package com.toy.backend.ledger.inbound

import com.toy.backend.ledger.entries.EntrySource
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * 실제 수신 문자 (2026-08-19 실패, `ledger_inbound_messages`).
 *
 * **한 줄에 다 들어 있고 시각이 없다.** 같은 결제의 승인 문자는 아래 [approvalText]처럼
 * 여러 줄에 시각까지 붙어 따로 왔다 — 취소만 이 형식으로 온다.
 */
private val cancelNoticeText =
    """
    [Web발신]
    [현대카드] 이*지님 08/10 (주)이니시스 - (주)공영홈쇼핑 사용 22,320원 취소처리되었습니다
    """.trimIndent()

/** 위 취소가 가리키는 원 승인 문자(2026-08-10 수신). 가맹점이 `(주)공영홈쇼핑`으로 저장됐다. */
private val approvalText =
    """
    [Web발신]
    대한항공카드 승인
    이*지
    22,320원 일시불
    08/10 18:21
    (주)공영홈쇼핑
    누적5,638,645원
    """.trimIndent()

class CardCancelNoticeParserTest :
    BehaviorSpec({
        val parser = CardCancelNoticeParser()
        val receivedAt = LocalDateTime.of(2026, 8, 19, 14, 30)

        Given("PG를 거친 결제의 취소 알림") {
            When("supports") {
                Then("처리 가능") { parser.supports(cancelNoticeText).shouldBeTrue() }
            }
            When("parse") {
                val result = parser.parse(cancelNoticeText, receivedAt)
                Then("취소로 판별하고 금액을 뽑는다") {
                    result.kind shouldBe ParsedKind.CANCEL
                    result.amount shouldBe BigDecimal("22320")
                    result.currency shouldBe "KRW"
                    result.source shouldBe EntrySource.SMS
                }

                // 승인 문자는 `(주)공영홈쇼핑`으로 저장됐다. PG사(`(주)이니시스`)를 떼지 않으면
                // 가맹점이 달라 취소 매칭이 실패하고 음수 건이 따로 쌓인다.
                Then("PG사를 떼고 실가맹점만 남긴다") {
                    result.merchant shouldBe "(주)공영홈쇼핑"
                }

                // **그날의 끝으로 잡는다.** 매칭 창이 `entryAt <= occurredAt`이라
                // 자정으로 잡으면 같은 날 18:21에 승인된 건이 창 밖으로 밀려난다.
                Then("일시는 그 날짜의 끝이다") {
                    result.occurredAt shouldBe LocalDateTime.of(2026, 8, 10, 23, 59, 59, 999_999_999)
                }
            }
        }

        // PG를 안 거치면 가맹점이 하나만 온다. 그때 이름을 잘라 먹으면 안 된다.
        Given("PG 없이 가맹점만 있는 취소 알림") {
            val text = cancelNoticeText.replace("(주)이니시스 - (주)공영홈쇼핑", "(주)공영홈쇼핑")
            When("parse") {
                Then("가맹점을 그대로 쓴다") {
                    parser.parse(text, receivedAt).merchant shouldBe "(주)공영홈쇼핑"
                }
            }
        }

        /*
         * **PG가 아닌데 이름에 ` - `가 든 가맹점을 잘라 먹으면 안 된다.** 승인 문자는 전체
         * 이름을 싣고 있으므로, 앞부분을 버리면 가맹점이 달라져 매칭이 실패하고 음수 건이
         * 따로 쌓인다. 「구분자가 있으면 무조건 자른다」가 아니라 앞이 PG인지 가려야 한다.
         */
        Given("PG가 아닌데 이름에 구분자가 든 가맹점") {
            val text = cancelNoticeText.replace("(주)이니시스 - (주)공영홈쇼핑", "공차 - 강남점")
            When("parse") {
                Then("이름을 통째로 유지한다") {
                    parser.parse(text, receivedAt).merchant shouldBe "공차 - 강남점"
                }
            }
        }

        Given("연도 경계") {
            When("1월에 12월 거래의 취소 알림을 받으면") {
                val text = cancelNoticeText.replace("08/10", "12/31")
                val result = parser.parse(text, LocalDateTime.of(2027, 1, 2, 9, 0))
                Then("전년도로 보정") {
                    result.occurredAt shouldBe LocalDateTime.of(2026, 12, 31, 23, 59, 59, 999_999_999)
                }
            }
        }

        // 이 파서가 다른 형식을 가로채면 시각·통화 처리가 통째로 빠진다.
        Given("다른 형식의 문자") {
            When("일반 승인 문자") {
                Then("처리 불가") { parser.supports(approvalText).shouldBeFalse() }
            }
            When("무관한 텍스트") {
                Then("처리 불가") { parser.supports("안녕하세요 광고 문자입니다").shouldBeFalse() }
            }
        }
    })
