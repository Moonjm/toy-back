package com.toy.backend.dispatch

import com.toy.backend.common.exception.CustomException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.LocalDate

/**
 * **보낸 날짜만 갱신한다.** 월 전체를 지우고 다시 넣으면 이전에 확정한 날짜가 사라진다 —
 * 잘린 변경분 사진은 그 달의 일부만 담고 있다.
 */
class DispatchCommandServiceTest :
    BehaviorSpec({
        val shiftRepository = mockk<DispatchShiftRepository>(relaxed = true)
        // relaxed 모드는 JpaRepository.save()의 제네릭 반환 타입(<S : T> S save(S))을
        // 못 풀어 ClassCastException을 낸다. 이 저장소의 다른 테스트들과 같은 방식으로 직접 답한다.
        every { shiftRepository.save(any()) } answers { firstArg() }
        val service = DispatchCommandService(shiftRepository)

        Given("새 날짜를 저장할 때") {
            every { shiftRepository.findByRoleAndWorkDate(any(), any()) } returns null

            service.saveShifts(
                ShiftSaveRequest(
                    role = DispatchRole.FATHER,
                    days = listOf(ShiftSaveDay(LocalDate.of(2026, 8, 1), working = true, slot = 1, note = null)),
                ),
            )

            Then("새 행이 생긴다") {
                val saved = slot<DispatchShift>()
                verify { shiftRepository.save(capture(saved)) }
                saved.captured.workDate shouldBe LocalDate.of(2026, 8, 1)
                saved.captured.working shouldBe true
                saved.captured.slot shouldBe 1
            }
        }

        Given("이미 저장된 날짜를 다시 보낼 때") {
            val existing =
                DispatchShift(DispatchRole.FATHER, LocalDate.of(2026, 8, 1), working = false, slot = null, note = "휴")
            every {
                shiftRepository.findByRoleAndWorkDate(DispatchRole.FATHER, LocalDate.of(2026, 8, 1))
            } returns existing

            service.saveShifts(
                ShiftSaveRequest(
                    role = DispatchRole.FATHER,
                    days = listOf(ShiftSaveDay(LocalDate.of(2026, 8, 1), working = true, slot = 2, note = null)),
                ),
            )

            Then("기존 행이 갱신된다") {
                existing.working shouldBe true
                existing.slot shouldBe 2
                existing.note shouldBe null
            }

            Then("새로 만들지 않는다") {
                verify(exactly = 0) { shiftRepository.save(any()) }
            }
        }

        // 사진이 그 날짜의 원본이므로 사진에 없는 값은 남기지 않는다. 하루 편집으로 넣은
        // 근무조가 남으면 휴무인데 근무조가 붙은 행이 무인증 조회로 나간다.
        Given("하루 편집으로 근무조가 들어간 날을 사진으로 다시 확정할 때") {
            val existing =
                DispatchShift(DispatchRole.MOTHER, LocalDate.of(2026, 8, 1), working = true, slotCode = "A")
            every {
                shiftRepository.findByRoleAndWorkDate(DispatchRole.MOTHER, LocalDate.of(2026, 8, 1))
            } returns existing

            service.saveShifts(
                ShiftSaveRequest(
                    role = DispatchRole.MOTHER,
                    days = listOf(ShiftSaveDay(LocalDate.of(2026, 8, 1), working = false, slot = null, note = "휴")),
                ),
            )

            Then("사진에 없는 근무조는 지워진다") {
                existing.working shouldBe false
                existing.slotCode shouldBe null
            }
        }

        val date = LocalDate.of(2026, 8, 15)

        Given("하루 편집으로 아빠만 보낼 때") {
            every { shiftRepository.findByRoleAndWorkDate(any(), any()) } returns null

            service.editDay(date, DayEditRequest(father = RoleEditRequest(working = true, slot = 3), mother = null))

            Then("아빠 행만 생긴다") {
                val saved = slot<DispatchShift>()
                verify(exactly = 1) { shiftRepository.save(capture(saved)) }
                saved.captured.role shouldBe DispatchRole.FATHER
                saved.captured.workDate shouldBe date
                saved.captured.working shouldBe true
                saved.captured.slot shouldBe 3
            }

            // 「저장된 적 없음」과 「휴무」의 구분을 저장 쪽에서 무너뜨리지 않는다.
            Then("손대지 않은 엄마는 조회조차 하지 않는다") {
                verify(exactly = 0) { shiftRepository.findByRoleAndWorkDate(DispatchRole.MOTHER, any()) }
            }
        }

        Given("하루 편집으로 엄마만 보낼 때") {
            every { shiftRepository.findByRoleAndWorkDate(any(), any()) } returns null

            service.editDay(date, DayEditRequest(father = null, mother = RoleEditRequest(working = true, slotCode = "A")))

            Then("엄마 예외 행이 근무조와 함께 생긴다 — 조회가 패턴 대신 이 값을 준다") {
                val saved = slot<DispatchShift>()
                verify(exactly = 1) { shiftRepository.save(capture(saved)) }
                saved.captured.role shouldBe DispatchRole.MOTHER
                saved.captured.working shouldBe true
                saved.captured.slotCode shouldBe "A"
            }
        }

        Given("이미 저장된 날짜를 하루 편집할 때") {
            val existing = DispatchShift(DispatchRole.FATHER, date, working = false, slot = null, note = "휴")
            every { shiftRepository.findByRoleAndWorkDate(DispatchRole.FATHER, date) } returns existing

            service.editDay(date, DayEditRequest(father = RoleEditRequest(working = true, slot = 2), mother = null))

            Then("기존 행이 갱신되고 레코드는 하나로 유지된다") {
                existing.working shouldBe true
                existing.slot shouldBe 2
                verify(exactly = 0) { shiftRepository.save(any()) }
            }

            // 사진에서 읽은 원문을 하루 편집 한 번에 날리지 않는다. 이 경로는 note를 읽지도 쓰지도 않는다.
            Then("사진에서 읽은 note가 남는다") {
                existing.note shouldBe "휴"
            }
        }

        Given("휴무인데 직전에 고른 순번이 함께 실려 올 때") {
            every { shiftRepository.findByRoleAndWorkDate(any(), any()) } returns null

            service.editDay(
                date,
                DayEditRequest(
                    father = RoleEditRequest(working = false, slot = 3),
                    mother = RoleEditRequest(working = false, slotCode = "A"),
                ),
            )

            Then("거절하지 않고 순번을 null로 눕혀 저장한다") {
                val saved = mutableListOf<DispatchShift>()
                verify(exactly = 2) { shiftRepository.save(capture(saved)) }
                saved.all { it.slot == null && it.slotCode == null } shouldBe true
            }
        }

        Given("역할이 둘 다 없을 때") {
            Then("400이다 — 조용히 성공시키면 값이 안 들어간 것을 모른다") {
                val thrown =
                    shouldThrow<CustomException> {
                        service.editDay(date, DayEditRequest(father = null, mother = null))
                    }
                thrown.errorCode shouldBe DispatchErrorCode.DAY_EDIT_ROLE_REQUIRED
            }
        }

        Given("엄마에게 아빠 순번(slot)을 보낼 때") {
            Then("400이다") {
                val thrown =
                    shouldThrow<CustomException> {
                        service.editDay(date, DayEditRequest(father = null, mother = RoleEditRequest(working = true, slot = 1)))
                    }
                thrown.errorCode shouldBe DispatchErrorCode.MOTHER_SLOT_NOT_ALLOWED
            }
        }

        Given("아빠에게 엄마 근무조(slotCode)를 보낼 때") {
            Then("400이다") {
                val thrown =
                    shouldThrow<CustomException> {
                        service.editDay(date, DayEditRequest(father = RoleEditRequest(working = true, slotCode = "A"), mother = null))
                    }
                thrown.errorCode shouldBe DispatchErrorCode.FATHER_SLOT_CODE_NOT_ALLOWED
            }
        }

        Given("아빠는 멀쩡한데 엄마 항목이 틀렸을 때") {
            every { shiftRepository.findByRoleAndWorkDate(any(), any()) } returns null

            Then("아빠도 저장되지 않는다 — 검증은 저장 전에 모아서 한다") {
                shouldThrow<CustomException> {
                    service.editDay(
                        date,
                        DayEditRequest(
                            father = RoleEditRequest(working = true, slot = 3),
                            mother = RoleEditRequest(working = true, slot = 1),
                        ),
                    )
                }
                verify(exactly = 0) { shiftRepository.save(any()) }
            }
        }
    })
