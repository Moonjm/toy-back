package com.toy.backend.dispatch

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/**
 * **기준일 이전 날짜에서 깨지기 쉽다.** Kotlin `%`는 음수 나머지를 음수로 주므로
 * `Math.floorMod`를 써야 한다. 기준일을 8/1로 잡고 7월을 조회하는 것이 실제 사용 흐름이다.
 */
class DispatchPatternExpanderTest :
    BehaviorSpec({
        Given("하루 휴무 뒤 이틀 근무가 3일 주기로 도는 패턴") {
            When("기준일 자신을 물으면") {
                Then("휴무다") {
                    DispatchPatternExpander.isWorking(LocalDate.of(2026, 8, 1)) shouldBe false
                }
            }

            When("기준일 이후를 물으면") {
                Then("주기대로 돈다") {
                    DispatchPatternExpander.isWorking(LocalDate.of(2026, 8, 2)) shouldBe true
                    DispatchPatternExpander.isWorking(LocalDate.of(2026, 8, 3)) shouldBe true
                    DispatchPatternExpander.isWorking(LocalDate.of(2026, 8, 4)) shouldBe false
                }
            }

            When("기준일 이전을 물으면") {
                Then("음수 오프셋이 올바로 감긴다") {
                    DispatchPatternExpander.isWorking(LocalDate.of(2026, 7, 31)) shouldBe true
                    DispatchPatternExpander.isWorking(LocalDate.of(2026, 7, 30)) shouldBe true
                    DispatchPatternExpander.isWorking(LocalDate.of(2026, 7, 29)) shouldBe false
                    DispatchPatternExpander.isWorking(LocalDate.of(2026, 7, 1)) shouldBe true
                }
            }

            When("8월 전체를 펼치면") {
                val offDays =
                    (1..31)
                        .map { LocalDate.of(2026, 8, it) }
                        .filterNot { DispatchPatternExpander.isWorking(it) }
                        .map { it.dayOfMonth }

                Then("휴무는 3일 간격으로 열한 번이다") {
                    offDays shouldBe listOf(1, 4, 7, 10, 13, 16, 19, 22, 25, 28, 31)
                }
            }

            When("해를 넘겨 물으면") {
                Then("연도 경계에서도 주기가 이어진다") {
                    // 2026-08-01부터 2027-01-01까지 153일 → floorMod(153, 3) = 0 → 휴무
                    DispatchPatternExpander.isWorking(LocalDate.of(2027, 1, 1)) shouldBe false
                }
            }
        }
    })
