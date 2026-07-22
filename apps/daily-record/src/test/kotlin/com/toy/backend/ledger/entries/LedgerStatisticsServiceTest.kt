package com.toy.backend.ledger.entries

import com.toy.backend.ledger.dummyLedgerEntry
import com.toy.backend.user.UserRepository
import com.toy.backend.user.entity.dummyUser
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.time.LocalDateTime

class LedgerStatisticsServiceTest :
    BehaviorSpec({
        val repository = mockk<LedgerEntryRepository>()
        val userRepository = mockk<UserRepository>()
        val service = LedgerStatisticsService(repository, userRepository)

        val user = dummyUser()

        beforeTest {
            every { userRepository.findByUsername("testuser") } returns user
        }

        Given("6개월치 지출 내역") {
            val entries =
                listOf(
                    // 기준월(7월): 원화 3건(같은 가맹점 2건) + 외화 1건 + 수입 1건
                    dummyLedgerEntry(
                        user = user, entryAt = LocalDateTime.of(2026, 7, 5, 12, 0),
                        amount = BigDecimal("10000"), merchant = "스타벅스", source = EntrySource.SMS, id = 1L,
                    ),
                    dummyLedgerEntry(
                        user = user, entryAt = LocalDateTime.of(2026, 7, 10, 12, 0),
                        amount = BigDecimal("20000"), merchant = "스타벅스", source = EntrySource.SMS, id = 2L,
                    ),
                    dummyLedgerEntry(
                        user = user, entryAt = LocalDateTime.of(2026, 7, 10, 18, 0),
                        amount = BigDecimal("50000"), merchant = "이마트", source = EntrySource.MANUAL, id = 3L,
                    ),
                    dummyLedgerEntry(
                        user = user, entryAt = LocalDateTime.of(2026, 7, 12, 9, 0),
                        amount = BigDecimal("1000"), currency = "JPY", merchant = "세븐일레븐", source = EntrySource.SMS, id = 4L,
                    ),
                    dummyLedgerEntry(
                        user = user, entryAt = LocalDateTime.of(2026, 7, 15, 9, 0),
                        amount = BigDecimal("99999"), type = EntryType.INCOME, merchant = "월급", source = EntrySource.MANUAL, id = 5L,
                    ),
                    // 전월(6월): 원화 1건
                    dummyLedgerEntry(
                        user = user, entryAt = LocalDateTime.of(2026, 6, 20, 12, 0),
                        amount = BigDecimal("40000"), merchant = "쿠팡", source = EntrySource.SMS, id = 6L,
                    ),
                )
            every { repository.search(user, any(), any(), null) } returns entries

            When("2026-07 기준 통계") {
                val result = service.statistics("testuser", java.time.YearMonth.of(2026, 7))

                Then("월별 추이는 6개월, 기준월 합계는 원화 지출만") {
                    result.monthlyTrend.size shouldBe 6
                    result.monthlyTrend.last().yearMonth shouldBe "2026-07"
                    result.monthlyTrend.last().krwTotal shouldBe BigDecimal("80000")
                    result.monthlyTrend[4].krwTotal shouldBe BigDecimal("40000") // 6월
                    result.monthlyTrend[0].krwTotal shouldBe BigDecimal.ZERO // 2월
                }

                Then("출처별 구성은 금액 내림차순, 수입·외화 제외") {
                    result.sourceBreakdown.map { it.source } shouldBe listOf(EntrySource.MANUAL, EntrySource.SMS)
                    result.sourceBreakdown[0].krwTotal shouldBe BigDecimal("50000")
                    result.sourceBreakdown[1].krwTotal shouldBe BigDecimal("30000")
                }

                Then("외화는 통화별 합계") {
                    result.foreignTotals.size shouldBe 1
                    result.foreignTotals[0].currency shouldBe "JPY"
                    result.foreignTotals[0].total shouldBe BigDecimal("1000")
                }

                Then("가맹점 TOP은 금액순 + 횟수") {
                    result.topMerchants[0].merchant shouldBe "이마트"
                    result.topMerchants[1].merchant shouldBe "스타벅스"
                    result.topMerchants[1].krwTotal shouldBe BigDecimal("30000")
                    result.topMerchants[1].count shouldBe 2
                }

                Then("최대 단건과 일평균(지출 있는 날 기준)") {
                    result.maxEntry?.merchant shouldBe "이마트"
                    result.maxEntry?.amount shouldBe BigDecimal("50000")
                    // 지출일: 7/5, 7/10 → 80000 / 2 = 40000
                    result.dailyAverage shouldBe BigDecimal("40000")
                }
            }
        }
    })
