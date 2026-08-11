package com.toy.backend.dispatch

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate

/**
 * 아빠와 엄마는 저장 경로가 다르다 — **아빠는 확정 저장된 날짜만, 엄마는 패턴으로 범위 전체.**
 * 읽는 쪽(웹 달력)은 그 차이를 몰라야 하므로 여기서 같은 모양으로 합친다.
 *
 * 아빠의 「아직 인식하지 않은 날」을 휴무로 채우면 안 된다. 「쉬는 날」과 구분이 사라진다.
 */
class DispatchQueryServiceTest :
    BehaviorSpec({
        val shiftRepository = mockk<DispatchShiftRepository>()
        val patternRepository = mockk<DispatchPatternRepository>()
        val service = DispatchQueryService(shiftRepository, patternRepository)

        val from = LocalDate.of(2026, 8, 1)
        val to = LocalDate.of(2026, 8, 3)

        Given("아빠 확정분 하루와 엄마 패턴이 있을 때") {
            every { shiftRepository.findByWorkDateBetween(from, to) } returns
                listOf(
                    DispatchShift(DispatchRole.FATHER, LocalDate.of(2026, 8, 1), working = true, slot = 1),
                )
            every { patternRepository.findByRole(DispatchRole.MOTHER) } returns
                DispatchPattern(DispatchRole.MOTHER, 3, "1,2", LocalDate.of(2026, 8, 8))

            val days = service.findRange(from, to).days

            Then("아빠는 확정된 하루만 나온다") {
                val father = days.filter { it.role == DispatchRole.FATHER }
                father.size shouldBe 1
                father[0].date shouldBe LocalDate.of(2026, 8, 1)
                father[0].working shouldBe true
                father[0].slot shouldBe 1
            }

            Then("엄마는 범위 전체가 패턴으로 채워진다") {
                val mother = days.filter { it.role == DispatchRole.MOTHER }.sortedBy { it.date }
                mother.size shouldBe 3
                mother[0].working shouldBe true // 8/1
                mother[1].working shouldBe false // 8/2
                mother[2].working shouldBe true // 8/3
            }

            Then("엄마는 순번이 아직 없어 slot이 비어 있다") {
                days.filter { it.role == DispatchRole.MOTHER }.all { it.slot == null } shouldBe true
            }
        }

        Given("엄마 예외가 저장돼 있을 때") {
            every { shiftRepository.findByWorkDateBetween(from, to) } returns
                listOf(
                    // 패턴상 8/1은 근무인데 예외로 휴무를 저장했다
                    DispatchShift(DispatchRole.MOTHER, LocalDate.of(2026, 8, 1), working = false, note = "연차"),
                )
            every { patternRepository.findByRole(DispatchRole.MOTHER) } returns
                DispatchPattern(DispatchRole.MOTHER, 3, "1,2", LocalDate.of(2026, 8, 8))

            val days = service.findRange(from, to).days

            Then("예외가 패턴 계산을 덮어쓴다") {
                val day = days.first { it.role == DispatchRole.MOTHER && it.date == LocalDate.of(2026, 8, 1) }
                day.working shouldBe false
                day.note shouldBe "연차"
            }

            Then("예외가 없는 날은 그대로 패턴이다") {
                val day = days.first { it.role == DispatchRole.MOTHER && it.date == LocalDate.of(2026, 8, 3) }
                day.working shouldBe true
            }
        }

        Given("패턴이 등록되지 않았을 때") {
            every { shiftRepository.findByWorkDateBetween(from, to) } returns emptyList()
            every { patternRepository.findByRole(DispatchRole.MOTHER) } returns null

            Then("엄마 일정 없이 조회가 성공한다") {
                service.findRange(from, to).days.none { it.role == DispatchRole.MOTHER } shouldBe true
            }
        }
    })
