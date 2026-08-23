package com.toy.backend.dispatch

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import java.time.YearMonth

/**
 * 아빠와 엄마는 값이 들어오는 경로가 다르다(사진 인식 / 손 입력). 읽는 쪽(웹 달력)은 그 차이를
 * 몰라야 하므로 여기서 같은 모양으로 합친다.
 *
 * **저장되지 않은 날을 채우지 않는다.** 휴무로 채우면 「쉬는 날」과 「아직 모르는 날」의 구분이
 * 사라진다. 한때 엄마 몫만 3일 주기 패턴으로 달 전체를 채웠으나 근무 형태가 바뀌며 걷어냈다.
 *
 * **조회 단위는 연월 하나다.** 이 엔드포인트는 무인증이라 임의의 기간을 받으면 요청 한 번으로
 * 범위를 무한정 넓힐 수 있다. 한 달에 가두면 상한 검사 자체가 필요 없다.
 */
class DispatchQueryServiceTest :
    BehaviorSpec({
        val shiftRepository = mockk<DispatchShiftRepository>()
        val service = DispatchQueryService(shiftRepository)

        val yearMonth = YearMonth.of(2026, 8)
        val from = yearMonth.atDay(1)
        val to = yearMonth.atEndOfMonth()

        Given("두 사람의 근무가 저장돼 있을 때") {
            every { shiftRepository.findByWorkDateBetween(from, to) } returns
                listOf(
                    DispatchShift(DispatchRole.MOTHER, LocalDate.of(2026, 8, 2), working = true, slotCode = "A"),
                    DispatchShift(DispatchRole.FATHER, LocalDate.of(2026, 8, 1), working = true, slot = 1),
                    DispatchShift(DispatchRole.MOTHER, LocalDate.of(2026, 8, 1), working = false, note = "연차"),
                )

            val days = service.findMonth(yearMonth).days

            Then("저장된 것만 나온다 — 없는 날을 만들어 내지 않는다") {
                days.size shouldBe 3
            }

            Then("날짜순으로, 같은 날은 역할순으로 정렬된다") {
                days.map { it.date to it.role } shouldBe
                    listOf(
                        LocalDate.of(2026, 8, 1) to DispatchRole.FATHER,
                        LocalDate.of(2026, 8, 1) to DispatchRole.MOTHER,
                        LocalDate.of(2026, 8, 2) to DispatchRole.MOTHER,
                    )
            }

            Then("아빠의 순번이 실린다") {
                days.first { it.role == DispatchRole.FATHER }.slot shouldBe 1
            }

            Then("엄마의 근무조와 비고가 실린다") {
                val second = days.first { it.role == DispatchRole.MOTHER && it.date == LocalDate.of(2026, 8, 2) }
                second.slotCode shouldBe "A"
                val first = days.first { it.role == DispatchRole.MOTHER && it.date == LocalDate.of(2026, 8, 1) }
                first.working shouldBe false
                first.note shouldBe "연차"
            }
        }

        Given("아무것도 저장되지 않았을 때") {
            every { shiftRepository.findByWorkDateBetween(from, to) } returns emptyList()

            Then("빈 달이 나온다 — 엄마 몫도 만들어 내지 않는다") {
                service.findMonth(yearMonth).days shouldBe emptyList()
            }
        }

        Given("다른 달") {
            val september = YearMonth.of(2026, 9)

            Then("그 달의 첫날과 마지막 날로만 조회한다") {
                every {
                    shiftRepository.findByWorkDateBetween(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))
                } returns emptyList()

                service.findMonth(september).days shouldBe emptyList()
            }
        }
    })
