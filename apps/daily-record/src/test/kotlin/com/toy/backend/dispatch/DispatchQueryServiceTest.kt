package com.toy.backend.dispatch

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import java.time.YearMonth

/**
 * 아빠와 엄마는 저장 경로가 다르다 — **아빠는 확정 저장된 날짜만, 엄마는 패턴으로 범위 전체.**
 * 읽는 쪽(웹 달력)은 그 차이를 몰라야 하므로 여기서 같은 모양으로 합친다.
 *
 * 아빠의 「아직 인식하지 않은 날」을 휴무로 채우면 안 된다. 「쉬는 날」과 구분이 사라진다.
 *
 * **조회 단위는 연월 하나다.** 이 엔드포인트는 무인증이고 엄마 몫은 저장 여부와 무관하게
 * 하루씩 만들어 내므로, 임의의 기간을 받으면 요청 한 번으로 수백만 건을 만들어 라즈베리파이
 * 힙이 터진다. 범위를 구조적으로 한 달에 가두면 상한 검사 자체가 필요 없다.
 */
class DispatchQueryServiceTest :
    BehaviorSpec({
        val shiftRepository = mockk<DispatchShiftRepository>()
        val service = DispatchQueryService(shiftRepository)

        val yearMonth = YearMonth.of(2026, 8)
        val from = yearMonth.atDay(1)
        val to = yearMonth.atEndOfMonth()

        Given("아빠 확정분 하루와 엄마 패턴이 있을 때") {
            every { shiftRepository.findByWorkDateBetween(from, to) } returns
                listOf(
                    DispatchShift(DispatchRole.FATHER, LocalDate.of(2026, 8, 1), working = true, slot = 1),
                )

            val days = service.findMonth(yearMonth).days

            Then("아빠는 확정된 하루만 나온다") {
                val father = days.filter { it.role == DispatchRole.FATHER }
                father.size shouldBe 1
                father[0].date shouldBe LocalDate.of(2026, 8, 1)
                father[0].working shouldBe true
                father[0].slot shouldBe 1
            }

            Then("엄마는 달 전체가 패턴으로 채워진다") {
                val mother = days.filter { it.role == DispatchRole.MOTHER }.sortedBy { it.date }
                mother.size shouldBe 31
                mother[0].working shouldBe false // 8/1
                mother[1].working shouldBe true // 8/2
                mother[2].working shouldBe true // 8/3
            }

            Then("엄마 휴무는 1·4·7·10·13·16·19·22·25·28·31 열하루다") {
                // 기준일 8/1이 오프셋 0(휴무)이고 3일 주기다.
                val off =
                    days
                        .filter { it.role == DispatchRole.MOTHER && !it.working }
                        .map { it.date.dayOfMonth }
                off shouldBe listOf(1, 4, 7, 10, 13, 16, 19, 22, 25, 28, 31)
            }

            Then("엄마는 순번이 아직 없어 slot이 비어 있다") {
                days.filter { it.role == DispatchRole.MOTHER }.all { it.slot == null } shouldBe true
            }

            Then("패턴에서 만들어진 엄마 기본값은 slotCode가 비어 있다") {
                days.filter { it.role == DispatchRole.MOTHER }.all { it.slotCode == null } shouldBe true
            }
        }

        Given("엄마 근무조가 저장돼 있을 때") {
            every { shiftRepository.findByWorkDateBetween(from, to) } returns
                listOf(
                    DispatchShift(
                        DispatchRole.MOTHER,
                        LocalDate.of(2026, 8, 2),
                        working = true,
                        slotCode = "A",
                    ),
                )

            val days = service.findMonth(yearMonth).days

            Then("저장된 근무조가 응답에 실린다") {
                val day = days.first { it.role == DispatchRole.MOTHER && it.date == LocalDate.of(2026, 8, 2) }
                day.slotCode shouldBe "A"
            }
        }

        Given("엄마 예외가 저장돼 있을 때") {
            every { shiftRepository.findByWorkDateBetween(from, to) } returns
                listOf(
                    // 패턴상 8/2는 근무인데 예외로 휴무를 저장했다. **패턴이 휴무라고 하는 날을
                    // 고르면 예외가 없어도 같은 답이 나와** 덮어쓰기가 되는지 알 수 없다.
                    DispatchShift(DispatchRole.MOTHER, LocalDate.of(2026, 8, 2), working = false, note = "연차"),
                )

            val days = service.findMonth(yearMonth).days

            Then("예외가 패턴 계산을 덮어쓴다") {
                val day = days.first { it.role == DispatchRole.MOTHER && it.date == LocalDate.of(2026, 8, 2) }
                day.working shouldBe false
                day.note shouldBe "연차"
            }

            Then("예외가 없는 날은 그대로 패턴이다") {
                val day = days.first { it.role == DispatchRole.MOTHER && it.date == LocalDate.of(2026, 8, 3) }
                day.working shouldBe true
            }
        }

        Given("아무것도 저장되지 않았을 때") {
            every { shiftRepository.findByWorkDateBetween(from, to) } returns emptyList()

            val days = service.findMonth(yearMonth).days

            Then("엄마는 등록 절차 없이 달 전체가 나온다") {
                days.filter { it.role == DispatchRole.MOTHER }.size shouldBe 31
            }

            Then("아빠는 확정분이 없으므로 비어 있다") {
                days.none { it.role == DispatchRole.FATHER } shouldBe true
            }
        }

        Given("30일인 달") {
            val september = YearMonth.of(2026, 9)
            every {
                shiftRepository.findByWorkDateBetween(september.atDay(1), september.atEndOfMonth())
            } returns emptyList()

            Then("그 달의 길이만큼만 나온다 — 범위가 구조적으로 한 달에 갇힌다") {
                service.findMonth(september).days.size shouldBe 30
            }
        }
    })
