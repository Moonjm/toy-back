package com.toy.backend.maintenance

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

/**
 * 항목 합계는 **인식 결과가 맞았는지 판정하는 유일한 자동 검사**다(설계 문서 함정 1).
 * 음수 항목(`관리비차감`)을 빠뜨리거나 절댓값으로 더하면 그 검사가 조용히 거짓말을 한다.
 */
class MaintenanceBillTest :
    BehaviorSpec({
        fun bill() =
            MaintenanceBill(
                yearMonth = "2026-03",
                chargedAmount = BigDecimal("238370"),
                dueAmount = BigDecimal("238370"),
            )

        Given("음수 항목이 섞인 고지서") {
            When("항목을 채우면") {
                val target = bill()
                target.replaceItems(
                    listOf(
                        "일반관리비" to BigDecimal("34700"),
                        "관리비차감" to BigDecimal("-13790"),
                        "전기" to BigDecimal("47450"),
                    ),
                )

                Then("합계가 음수를 반영한다") {
                    target.itemTotal() shouldBe BigDecimal("68360")
                }

                Then("보낸 순서대로 번호가 매겨진다") {
                    target.items.map { it.name } shouldBe listOf("일반관리비", "관리비차감", "전기")
                    target.items.map { it.displayOrder } shouldBe listOf(0, 1, 2)
                }
            }
        }

        Given("이미 항목이 있는 고지서") {
            When("항목을 다시 채우면") {
                val target = bill()
                target.replaceItems(listOf("전기" to BigDecimal("100")))
                target.replaceItems(listOf("수도" to BigDecimal("200")))

                Then("이전 항목이 남지 않는다") {
                    target.items.map { it.name } shouldBe listOf("수도")
                }
            }
        }

        Given("항목이 하나도 없는 고지서") {
            When("합계를 내면") {
                Then("0이다") {
                    bill().itemTotal() shouldBe BigDecimal.ZERO
                }
            }
        }
    })
