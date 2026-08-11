package com.toy.backend.dispatch

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/**
 * **`anchorDate` 이전 날짜에서 깨지기 쉽다.** Kotlin `%`는 음수 나머지를 음수로 주므로
 * `Math.floorMod`를 써야 한다. 기준일을 8/8로 잡고 8/1을 조회하는 것이 실제 사용 흐름이다.
 */
class DispatchPatternExpanderTest :
    BehaviorSpec({
        val pattern =
            MotherPatternProperties(
                cycleDays = 3,
                workingOffsets = listOf(1, 2),
                anchorDate = LocalDate.of(2026, 8, 8),
            )

        Given("하루 휴무 뒤 이틀 근무가 3일 주기로 도는 패턴") {
            When("기준일 자신을 물으면") {
                Then("휴무다") {
                    DispatchPatternExpander.isWorking(pattern, LocalDate.of(2026, 8, 8)) shouldBe false
                }
            }

            When("기준일 이후를 물으면") {
                Then("주기대로 돈다") {
                    DispatchPatternExpander.isWorking(pattern, LocalDate.of(2026, 8, 9)) shouldBe true
                    DispatchPatternExpander.isWorking(pattern, LocalDate.of(2026, 8, 10)) shouldBe true
                    DispatchPatternExpander.isWorking(pattern, LocalDate.of(2026, 8, 11)) shouldBe false
                }
            }

            When("기준일 이전을 물으면") {
                Then("음수 오프셋이 올바로 감긴다") {
                    DispatchPatternExpander.isWorking(pattern, LocalDate.of(2026, 8, 7)) shouldBe true
                    DispatchPatternExpander.isWorking(pattern, LocalDate.of(2026, 8, 6)) shouldBe true
                    DispatchPatternExpander.isWorking(pattern, LocalDate.of(2026, 8, 5)) shouldBe false
                    DispatchPatternExpander.isWorking(pattern, LocalDate.of(2026, 8, 1)) shouldBe true
                }
            }

            When("8월 전체를 펼치면") {
                val offDays =
                    (1..31)
                        .map { LocalDate.of(2026, 8, it) }
                        .filterNot { DispatchPatternExpander.isWorking(pattern, it) }
                        .map { it.dayOfMonth }

                Then("휴무는 3일 간격으로 열 번이다") {
                    offDays shouldBe listOf(2, 5, 8, 11, 14, 17, 20, 23, 26, 29)
                }
            }

            When("해를 넘겨 물으면") {
                Then("연도 경계에서도 주기가 이어진다") {
                    // 2026-08-08부터 2027-01-01까지 146일 → floorMod(146, 3) = 2 → 근무
                    DispatchPatternExpander.isWorking(pattern, LocalDate.of(2027, 1, 1)) shouldBe true
                }
            }
        }
    })
