package com.toy.backend.dispatch

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
    })
