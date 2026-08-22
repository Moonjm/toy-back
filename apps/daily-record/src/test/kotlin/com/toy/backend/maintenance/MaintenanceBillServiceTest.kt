package com.toy.backend.maintenance

import com.toy.backend.common.exception.CustomException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.math.BigDecimal
import java.time.YearMonth

class MaintenanceBillServiceTest :
    BehaviorSpec({
        val repository = mockk<MaintenanceBillRepository>(relaxed = true)
        // relaxed 모드는 JpaRepository.save()의 제네릭 반환 타입을 못 풀어
        // ClassCastException을 낸다. 이 저장소의 다른 테스트들처럼 직접 답한다.
        every { repository.save(any()) } answers { firstArg() }
        val service = MaintenanceBillService(repository)

        fun request(yearMonth: String = "2026-03") =
            BillSaveRequest(
                yearMonth = YearMonth.parse(yearMonth),
                items =
                    listOf(
                        BillItemRequest("일반관리비", BigDecimal("34700")),
                        BillItemRequest("관리비차감", BigDecimal("-13790")),
                    ),
                chargedAmount = BigDecimal("20910"),
                dueAmount = BigDecimal("20910"),
                usage = BillUsage(electricityKwh = BigDecimal("261")),
            )

        Given("같은 달이 아직 없을 때") {
            every { repository.existsByYearMonth("2026-03") } returns false

            When("저장하면") {
                val captured = slot<MaintenanceBill>()
                every { repository.save(capture(captured)) } answers { firstArg() }
                service.create(request())

                Then("항목이 보낸 순서대로 들어간다") {
                    captured.captured.items.map { it.name } shouldBe listOf("일반관리비", "관리비차감")
                }

                Then("음수 항목이 음수로 남는다") {
                    captured.captured.itemTotal() shouldBe BigDecimal("20910")
                }

                Then("사용량이 컬럼에 들어간다") {
                    captured.captured.electricityKwh shouldBe BigDecimal("261")
                }
            }
        }

        Given("같은 달이 이미 있을 때") {
            every { repository.existsByYearMonth("2026-03") } returns true

            When("저장하면") {
                Then("409로 거부한다 — 조용히 덮어쓰지 않는다") {
                    val e = shouldThrow<CustomException> { service.create(request()) }
                    e.errorCode shouldBe MaintenanceErrorCode.BILL_ALREADY_EXISTS
                }

                Then("아무것도 저장하지 않는다") {
                    runCatching { service.create(request()) }
                    verify(exactly = 0) { repository.save(any()) }
                }
            }
        }

        Given("있는 달을 수정할 때") {
            val existing =
                MaintenanceBill(
                    yearMonth = "2026-03",
                    chargedAmount = BigDecimal("1"),
                    dueAmount = BigDecimal("1"),
                )
            existing.replaceItems(listOf("옛항목" to BigDecimal("1")))
            every { repository.findByYearMonth("2026-03") } returns existing

            When("수정하면") {
                service.replace(YearMonth.parse("2026-03"), request())

                Then("옛 항목이 남지 않는다") {
                    existing.items.map { it.name } shouldBe listOf("일반관리비", "관리비차감")
                }

                Then("요약 금액이 갱신된다") {
                    existing.chargedAmount shouldBe BigDecimal("20910")
                }
            }
        }

        Given("없는 달") {
            every { repository.findByYearMonth("2099-01") } returns null

            When("조회하면") {
                Then("404로 존재를 숨긴다") {
                    val e = shouldThrow<CustomException> { service.findOne(YearMonth.parse("2099-01")) }
                    e.errorCode shouldBe MaintenanceErrorCode.BILL_NOT_FOUND
                }
            }

            When("수정하면") {
                Then("404다") {
                    shouldThrow<CustomException> { service.replace(YearMonth.parse("2099-01"), request("2099-01")) }
                }
            }

            When("삭제하면") {
                Then("404다") {
                    shouldThrow<CustomException> { service.delete(YearMonth.parse("2099-01")) }
                }
            }
        }
    })
