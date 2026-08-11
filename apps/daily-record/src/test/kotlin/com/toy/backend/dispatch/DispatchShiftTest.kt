package com.toy.backend.dispatch

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/**
 * **판정은 `working`만 본다.** `slot`이 `null`이어도 근무일 수 있다(엄마는 순번이 아직 없다).
 * 「slot이 있으면 근무」로 판정하면 엄마 근무일이 전부 휴무로 읽힌다.
 */
class DispatchShiftTest :
    BehaviorSpec({
        Given("순번이 없는 근무일") {
            val shift =
                DispatchShift(
                    role = DispatchRole.MOTHER,
                    workDate = LocalDate.of(2026, 8, 1),
                    working = true,
                    slot = null,
                    note = null,
                )

            Then("slot이 없어도 근무다") {
                shift.working shouldBe true
                shift.slot shouldBe null
            }
        }

        Given("note만 있는 휴무일") {
            val shift =
                DispatchShift(
                    role = DispatchRole.FATHER,
                    workDate = LocalDate.of(2026, 8, 19),
                    working = false,
                    slot = null,
                    note = "간담회",
                )

            Then("note가 있어도 근무가 아니다") {
                shift.working shouldBe false
                shift.note shouldBe "간담회"
            }
        }

        Given("주기 안에서 일하는 날이 1,2인 패턴") {
            val pattern =
                DispatchPattern(
                    role = DispatchRole.MOTHER,
                    cycleDays = 3,
                    workingOffsets = "1,2",
                    anchorDate = LocalDate.of(2026, 8, 8),
                )

            Then("오프셋 목록으로 읽힌다") {
                pattern.workingOffsetList shouldBe listOf(1, 2)
            }
        }
    })
