package com.toy.backend.ledger.inbound

import com.toy.backend.ledger.entries.EntrySource
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.math.BigDecimal
import java.time.LocalDateTime

// 실제 알림톡 캡쳐의 iOS OCR 출력 (2026-07-19 수집) — 결제금액 헤더 포함, OCR이 어절 중간에 공백 삽입
private val ocrText =
    """
    • pay
    카카오페이
    알림톡 도착
    kakao
    결제금액
    26,810원
    페이머니 충전 후 결제가 완료되었어요.
    - 구매처: 롯데쇼핑(주)
    - 상품명: [APP전용][엘포인트 2,000점 적 립] 펩시콜라 제로슈거 라임향355ml 48캔 외 펩시BEST 모음전
    - 결제일시: 2026.07.18 14:44
    - 결제수단: 카카오페이머니
    *현금영수증 발행 여부는 구매처를 통해 확인 하세요.
    이용내역 보기
    내 소비 확인하기
    오후 2:44
    """.trimIndent()

// 카톡 본문 텍스트 복붙 (결제금액 헤더 없음, 상품명이 여러 줄로 줄바꿈)
private val pasteText =
    """
    페이머니 충전 후 결제가 완료되었어요.

    - 구매처: 롯데쇼핑(주)
    - 상품명: [APP전용][엘포인트 2,000점 적립]펩시콜라 제로슈거 라임향355ml 48캔
    외 펩시BEST 모음전
    - 결제일시: 2026.07.18 14:44
    - 결제수단: 카카오페이머니

    *현금영수증 발행 여부는 구매처를 통해 확인하세요.
    """.trimIndent()

class KakaoPayParserTest :
    BehaviorSpec({
        val parser = KakaoPayParser()
        val receivedAt = LocalDateTime.of(2026, 7, 18, 15, 0)

        Given("OCR 텍스트 (금액 헤더 포함)") {
            When("supports") {
                Then("처리 가능") { parser.supports(ocrText).shouldBeTrue() }
            }
            When("parse") {
                val result = parser.parse(ocrText, receivedAt)
                Then("금액·구매처·상품명·결제일시 추출") {
                    result.kind shouldBe ParsedKind.APPROVAL
                    result.amount shouldBe BigDecimal("26810")
                    result.currency shouldBe "KRW"
                    result.merchant shouldBe "롯데쇼핑(주)"
                    result.description shouldContain "펩시콜라"
                    result.occurredAt shouldBe LocalDateTime.of(2026, 7, 18, 14, 44)
                    result.source shouldBe EntrySource.KAKAO_PAY
                }
            }
        }

        Given("복붙 텍스트 (금액 헤더 없음)") {
            When("parse") {
                val result = parser.parse(pasteText, receivedAt)
                Then("금액 0으로 저장 (사용자가 수정), 여러 줄 상품명 이어붙임") {
                    result.amount shouldBe BigDecimal.ZERO
                    result.merchant shouldBe "롯데쇼핑(주)"
                    result.description shouldContain "외 펩시BEST 모음전"
                    result.occurredAt shouldBe LocalDateTime.of(2026, 7, 18, 14, 44)
                }
            }
        }
    })
