package com.toy.backend.maintenance

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.math.BigDecimal
import java.time.LocalDate

class MaintenanceTrendServiceTest :
    BehaviorSpec({
        val repository = mockk<MaintenanceBillRepository>()
        val service = MaintenanceTrendService(repository)
        val today = LocalDate.of(2026, 8, 21)

        fun bill(
            yearMonth: String,
            items: List<Pair<String, Int>>,
            heating: String? = null,
        ) = MaintenanceBill(
            yearMonth = yearMonth,
            chargedAmount = BigDecimal(items.sumOf { it.second }),
            dueAmount = BigDecimal(items.sumOf { it.second }),
            heatingGcal = heating?.let { BigDecimal(it) },
        ).also { it.replaceItems(items.map { (n, a) -> n to BigDecimal(a) }) }

        Given("난방이 있는 달과 없는 달") {
            every { repository.findByYearMonthGreaterThanEqualOrderByYearMonth(any()) } returns
                listOf(
                    bill("2026-03", listOf("전기" to 47450, "난방" to 24430), heating = "0.19"),
                    bill("2026-07", listOf("전기" to 95740)),
                )

            When("추이를 내면") {
                val response = service.trend(MaintenanceTrendService.DEFAULT_MONTHS, today)

                Then("난방이 없던 달에 0을 지어내지 않는다") {
                    val july = response.months.single { it.yearMonth == "2026-07" }
                    july.items.map { it.name } shouldBe listOf("전기")
                    july.usage.heatingGcal.shouldBeNull()
                }

                Then("난방이 있던 달은 그대로 나온다") {
                    val march = response.months.single { it.yearMonth == "2026-03" }
                    march.usage.heatingGcal shouldBe BigDecimal("0.19")
                }

                Then("오래된 달부터 나온다") {
                    response.months.map { it.yearMonth } shouldBe listOf("2026-03", "2026-07")
                }
            }
        }

        Given("기본 개월 수") {
            every { repository.findByYearMonthGreaterThanEqualOrderByYearMonth(any()) } returns emptyList()

            When("추이를 내면") {
                service.trend(MaintenanceTrendService.DEFAULT_MONTHS, today)

                Then("전년 동월이 범위에 들어오도록 13개월을 조회한다") {
                    // 2026-08 기준 13개월이면 2025-08부터다. 12로 두면 전년 동월이 빠져 비교가 안 된다.
                    verify { repository.findByYearMonthGreaterThanEqualOrderByYearMonth("2025-08") }
                }
            }
        }

        Given("터무니없이 큰 개월 수") {
            every { repository.findByYearMonthGreaterThanEqualOrderByYearMonth(any()) } returns emptyList()

            When("추이를 내면") {
                service.trend(9999, today)

                Then("상한으로 자른다") {
                    verify { repository.findByYearMonthGreaterThanEqualOrderByYearMonth("2021-09") }
                }
            }
        }

        Given("0 이하의 개월 수") {
            every { repository.findByYearMonthGreaterThanEqualOrderByYearMonth(any()) } returns emptyList()

            When("추이를 내면") {
                service.trend(0, today)

                Then("최소 한 달은 본다") {
                    verify { repository.findByYearMonthGreaterThanEqualOrderByYearMonth("2026-08") }
                }
            }
        }
    })
