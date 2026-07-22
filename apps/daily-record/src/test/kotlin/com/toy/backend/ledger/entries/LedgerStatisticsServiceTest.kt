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
import java.time.YearMonth

class LedgerStatisticsServiceTest :
    BehaviorSpec({
        val repository = mockk<LedgerEntryRepository>()
        val userRepository = mockk<UserRepository>()
        val service = LedgerStatisticsService(repository, userRepository)

        val user = dummyUser()
        // 기준월 2026-07 → 조회 창은 [2026-02-01, 2026-08-01). 경계가 어긋나면 mock이 매칭되지 않아 실패한다.
        val windowStart = LocalDateTime.of(2026, 2, 1, 0, 0)
        val windowEnd = LocalDateTime.of(2026, 8, 1, 0, 0)

        beforeTest {
            every { userRepository.findByUsername("testuser") } returns user
        }

        Given("6개월치 지출 내역 (반복·외화·수입 포함)") {
            val entries =
                listOf(
                    // 기준월(7월) 원화: 스타벅스 2건 + 이마트 1건 + 반복(넷플릭스) 1건
                    dummyLedgerEntry(
                        user = user,
                        entryAt = LocalDateTime.of(2026, 7, 5, 12, 0),
                        amount = BigDecimal("10000"),
                        merchant = "스타벅스",
                        source = EntrySource.SMS,
                        id = 1L,
                    ),
                    dummyLedgerEntry(
                        user = user,
                        entryAt = LocalDateTime.of(2026, 7, 10, 12, 0),
                        amount = BigDecimal("20000"),
                        merchant = "스타벅스",
                        source = EntrySource.SMS,
                        id = 2L,
                    ),
                    dummyLedgerEntry(
                        user = user,
                        entryAt = LocalDateTime.of(2026, 7, 10, 18, 0),
                        amount = BigDecimal("50000"),
                        merchant = "이마트",
                        source = EntrySource.MANUAL,
                        id = 3L,
                    ),
                    dummyLedgerEntry(
                        user = user,
                        entryAt = LocalDateTime.of(2026, 7, 3, 0, 0),
                        amount = BigDecimal("100000"),
                        merchant = "넷플릭스",
                        source = EntrySource.RECURRING,
                        id = 4L,
                    ),
                    // 기준월 외화·수입 — 원화 집계에서 제외
                    dummyLedgerEntry(
                        user = user,
                        entryAt = LocalDateTime.of(2026, 7, 12, 9, 0),
                        amount = BigDecimal("1000"),
                        currency = "JPY",
                        merchant = "세븐일레븐",
                        source = EntrySource.SMS,
                        id = 5L,
                    ),
                    dummyLedgerEntry(
                        user = user,
                        entryAt = LocalDateTime.of(2026, 7, 15, 9, 0),
                        amount = BigDecimal("99999"),
                        type = EntryType.INCOME,
                        merchant = "월급",
                        source = EntrySource.MANUAL,
                        id = 6L,
                    ),
                    // 전월(6월)
                    dummyLedgerEntry(
                        user = user,
                        entryAt = LocalDateTime.of(2026, 6, 20, 12, 0),
                        amount = BigDecimal("40000"),
                        merchant = "쿠팡",
                        source = EntrySource.SMS,
                        id = 7L,
                    ),
                )
            every { repository.search(user, windowStart, windowEnd, null) } returns entries

            When("2026-07 기준 통계") {
                val result = service.statistics("testuser", YearMonth.of(2026, 7))

                Then("월별 추이는 6개월, 기준월 합계는 원화 지출만") {
                    result.monthlyTrend.size shouldBe 6
                    result.monthlyTrend.first().yearMonth shouldBe "2026-02"
                    result.monthlyTrend.last().yearMonth shouldBe "2026-07"
                    result.monthlyTrend.last().krwTotal shouldBe BigDecimal("180000")
                    result.monthlyTrend[4].krwTotal shouldBe BigDecimal("40000") // 6월
                    result.monthlyTrend[0].krwTotal shouldBe BigDecimal.ZERO // 2월
                }

                Then("직전 기간(지난달) 합계") {
                    result.previousTotal shouldBe BigDecimal("40000")
                }

                Then("출처별 구성은 금액 내림차순, 수입·외화 제외") {
                    result.sourceBreakdown.map { it.source } shouldBe
                        listOf(EntrySource.RECURRING, EntrySource.MANUAL, EntrySource.SMS)
                    result.sourceBreakdown[0].krwTotal shouldBe BigDecimal("100000")
                }

                Then("외화는 통화별 합계") {
                    result.foreignTotals.size shouldBe 1
                    result.foreignTotals[0].currency shouldBe "JPY"
                    result.foreignTotals[0].total shouldBe BigDecimal("1000")
                }

                Then("가맹점 TOP은 반복 제외, 금액순 + 횟수") {
                    result.topMerchants.map { it.merchant } shouldBe listOf("이마트", "스타벅스")
                    result.topMerchants[1].krwTotal shouldBe BigDecimal("30000")
                    result.topMerchants[1].count shouldBe 2
                }

                Then("최대 단건은 반복 포함, 일평균은 지출 있는 날 기준") {
                    result.maxEntry?.merchant shouldBe "넷플릭스"
                    result.maxEntry?.amount shouldBe BigDecimal("100000")
                    // 지출일: 7/3, 7/5, 7/10 → 180000 / 3 = 60000
                    result.dailyAverage shouldBe BigDecimal("60000")
                }
            }
        }

        Given("기준월에 내역이 없으면") {
            every { repository.search(user, windowStart, windowEnd, null) } returns emptyList()

            When("2026-07 기준 통계") {
                val result = service.statistics("testuser", YearMonth.of(2026, 7))

                Then("모든 집계가 0/빈 값") {
                    result.monthlyTrend.size shouldBe 6
                    result.monthlyTrend.all { it.krwTotal == BigDecimal.ZERO } shouldBe true
                    result.previousTotal shouldBe BigDecimal.ZERO
                    result.sourceBreakdown shouldBe emptyList()
                    result.foreignTotals shouldBe emptyList()
                    result.topMerchants shouldBe emptyList()
                    result.maxEntry shouldBe null
                    result.dailyAverage shouldBe BigDecimal.ZERO
                }
            }
        }

        Given("가맹점이 6곳이면") {
            val entries =
                (1..6).map { i ->
                    dummyLedgerEntry(
                        user = user,
                        entryAt = LocalDateTime.of(2026, 7, i, 12, 0),
                        amount = BigDecimal(i * 1000),
                        merchant = "가맹점$i",
                        source = EntrySource.SMS,
                        id = i.toLong(),
                    )
                }
            every { repository.search(user, windowStart, windowEnd, null) } returns entries

            When("2026-07 기준 통계") {
                val result = service.statistics("testuser", YearMonth.of(2026, 7))

                Then("금액 상위 5곳만 남고 최소 금액 가맹점은 제외") {
                    result.topMerchants.size shouldBe 5
                    result.topMerchants.map { it.merchant } shouldBe
                        listOf("가맹점6", "가맹점5", "가맹점4", "가맹점3", "가맹점2")
                }
            }
        }

        Given("2년치 지출 내역 (연별 통계)") {
            // 기준연 2026 → 조회 창은 [2025-01-01, 2027-01-01). 지난해 합계까지 한 번에 계산한다.
            val yearlyWindowStart = LocalDateTime.of(2025, 1, 1, 0, 0)
            val yearlyWindowEnd = LocalDateTime.of(2027, 1, 1, 0, 0)
            val entries =
                listOf(
                    // 기준연(2026) 원화: 3월 30000 + 7월 50000 + 반복(넷플릭스) 100000
                    dummyLedgerEntry(
                        user = user,
                        entryAt = LocalDateTime.of(2026, 3, 5, 12, 0),
                        amount = BigDecimal("30000"),
                        merchant = "스타벅스",
                        source = EntrySource.SMS,
                        id = 1L,
                    ),
                    dummyLedgerEntry(
                        user = user,
                        entryAt = LocalDateTime.of(2026, 7, 10, 18, 0),
                        amount = BigDecimal("50000"),
                        merchant = "이마트",
                        source = EntrySource.MANUAL,
                        id = 2L,
                    ),
                    dummyLedgerEntry(
                        user = user,
                        entryAt = LocalDateTime.of(2026, 7, 3, 0, 0),
                        amount = BigDecimal("100000"),
                        merchant = "넷플릭스",
                        source = EntrySource.RECURRING,
                        id = 3L,
                    ),
                    // 기준연 외화 — 원화 집계 제외, 외화 합계 포함
                    dummyLedgerEntry(
                        user = user,
                        entryAt = LocalDateTime.of(2026, 5, 12, 9, 0),
                        amount = BigDecimal("2000"),
                        currency = "JPY",
                        merchant = "세븐일레븐",
                        source = EntrySource.SMS,
                        id = 4L,
                    ),
                    // 지난해(2025) — previousTotal에만 반영
                    dummyLedgerEntry(
                        user = user,
                        entryAt = LocalDateTime.of(2025, 5, 20, 12, 0),
                        amount = BigDecimal("20000"),
                        merchant = "쿠팡",
                        source = EntrySource.SMS,
                        id = 5L,
                    ),
                )
            every { repository.search(user, yearlyWindowStart, yearlyWindowEnd, null) } returns entries

            When("2026 기준 연별 통계") {
                val result = service.yearlyStatistics("testuser", 2026)

                Then("추이는 기준연 12개월, 월별 원화 합계") {
                    result.yearMonth shouldBe "2026"
                    result.monthlyTrend.size shouldBe 12
                    result.monthlyTrend.first().yearMonth shouldBe "2026-01"
                    result.monthlyTrend.last().yearMonth shouldBe "2026-12"
                    result.monthlyTrend[2].krwTotal shouldBe BigDecimal("30000") // 3월
                    result.monthlyTrend[6].krwTotal shouldBe BigDecimal("150000") // 7월
                    result.monthlyTrend[0].krwTotal shouldBe BigDecimal.ZERO // 1월
                }

                Then("직전 기간(지난해) 합계 — 기준연에는 미포함") {
                    result.previousTotal shouldBe BigDecimal("20000")
                    result.topMerchants.map { it.merchant } shouldBe listOf("이마트", "스타벅스")
                }

                Then("외화·최대 단건·일평균은 기준연 전체 기준") {
                    result.foreignTotals.size shouldBe 1
                    result.foreignTotals[0].total shouldBe BigDecimal("2000")
                    result.maxEntry?.merchant shouldBe "넷플릭스"
                    // 지출일: 3/5, 7/3, 7/10 → 180000 / 3 = 60000
                    result.dailyAverage shouldBe BigDecimal("60000")
                }
            }
        }

        Given("일평균이 나누어떨어지지 않으면") {
            val entries =
                listOf(
                    dummyLedgerEntry(
                        user = user,
                        entryAt = LocalDateTime.of(2026, 7, 1, 12, 0),
                        amount = BigDecimal("100"),
                        merchant = "가",
                        id = 1L,
                    ),
                    dummyLedgerEntry(
                        user = user,
                        entryAt = LocalDateTime.of(2026, 7, 2, 12, 0),
                        amount = BigDecimal("101"),
                        merchant = "나",
                        id = 2L,
                    ),
                )
            every { repository.search(user, windowStart, windowEnd, null) } returns entries

            When("2026-07 기준 통계") {
                val result = service.statistics("testuser", YearMonth.of(2026, 7))

                Then("원 단위 HALF_UP 반올림 (201/2 = 100.5 → 101)") {
                    result.dailyAverage shouldBe BigDecimal("101")
                }
            }
        }
    })
