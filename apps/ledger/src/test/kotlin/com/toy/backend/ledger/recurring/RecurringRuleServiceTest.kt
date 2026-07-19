package com.toy.backend.ledger.recurring

import com.toy.backend.common.entity.withId
import com.toy.backend.ledger.dummyLedgerEntry
import com.toy.backend.ledger.entries.EntrySource
import com.toy.backend.ledger.entries.EntryType
import com.toy.backend.ledger.entries.LedgerEntry
import com.toy.backend.ledger.entries.LedgerEntryRepository
import com.toy.backend.user.UserRepository
import com.toy.backend.user.entity.dummyUser
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.data.repository.findByIdOrNull
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

private fun dummyRule(
    user: com.toy.backend.user.User = dummyUser(),
    dayOfMonth: Int = 25,
    amount: BigDecimal = BigDecimal("500000"),
    active: Boolean = true,
    lastGeneratedMonth: String? = null,
    id: Long = 1L,
): RecurringRule =
    RecurringRule(
        user = user,
        dayOfMonth = dayOfMonth,
        amount = amount,
        currency = "KRW",
        type = EntryType.EXPENSE,
        merchant = "데이트비용",
        description = null,
        active = active,
        lastGeneratedMonth = lastGeneratedMonth,
    ).withId(id)

class RecurringRuleServiceTest :
    BehaviorSpec({
        val repository = mockk<RecurringRuleRepository>()
        val entryRepository = mockk<LedgerEntryRepository>()
        val userRepository = mockk<UserRepository>()
        val service = RecurringRuleService(repository, entryRepository, userRepository)

        val user = dummyUser()

        beforeTest {
            every { userRepository.findByUsername("testuser") } returns user
        }

        Given("규칙 등록 (entry 기반)") {
            When("dayOfMonth 생략 시") {
                val entry =
                    dummyLedgerEntry(
                        user = user,
                        entryAt = LocalDateTime.of(2026, 7, 25, 0, 0),
                        merchant = "넷플릭스",
                        id = 5L,
                    )
                every { entryRepository.findByIdOrNull(5L) } returns entry
                every { repository.save(any()) } answers { (firstArg() as RecurringRule).withId(2L) }

                val id = service.create("testuser", RecurringRuleCreateRequest(entryId = 5L))

                Then("entry 값 복사, 반복일은 entry 날짜의 일") {
                    id shouldBe 2L
                    verify {
                        repository.save(
                            match {
                                it.dayOfMonth == 25 &&
                                    it.amount == BigDecimal("18920") &&
                                    it.merchant == "넷플릭스" &&
                                    it.type == EntryType.EXPENSE
                            },
                        )
                    }
                }
            }
        }

        Given("스케줄러 생성 — generateDueEntries") {
            When("반복일이 지났고 이번 달 미생성이면") {
                val rule = dummyRule(dayOfMonth = 25, lastGeneratedMonth = "2026-06")
                every { repository.findAllByActiveTrue() } returns listOf(rule)
                every { entryRepository.save(any()) } answers { (firstArg() as LedgerEntry).withId(20L) }

                val count = service.generateDueEntries(LocalDate.of(2026, 7, 26))

                Then("entry 생성(source=RECURRING), lastGeneratedMonth 갱신") {
                    count shouldBe 1
                    rule.lastGeneratedMonth shouldBe "2026-07"
                    verify {
                        entryRepository.save(
                            match {
                                it.source == EntrySource.RECURRING &&
                                    it.entryAt == LocalDateTime.of(2026, 7, 25, 0, 0)
                            },
                        )
                    }
                }
            }

            When("이번 달 이미 생성했으면") {
                val rule = dummyRule(dayOfMonth = 25, lastGeneratedMonth = "2026-07")
                every { repository.findAllByActiveTrue() } returns listOf(rule)

                val count = service.generateDueEntries(LocalDate.of(2026, 7, 26))

                Then("생성하지 않음") { count shouldBe 0 }
            }

            When("반복일이 아직 안 됐으면") {
                val rule = dummyRule(dayOfMonth = 25, lastGeneratedMonth = "2026-06")
                every { repository.findAllByActiveTrue() } returns listOf(rule)

                val count = service.generateDueEntries(LocalDate.of(2026, 7, 10))

                Then("생성하지 않음") { count shouldBe 0 }
            }

            When("31일 규칙을 2월에 실행하면") {
                val rule = dummyRule(dayOfMonth = 31, lastGeneratedMonth = "2026-01")
                every { repository.findAllByActiveTrue() } returns listOf(rule)
                every { entryRepository.save(any()) } answers { (firstArg() as LedgerEntry).withId(21L) }

                val count = service.generateDueEntries(LocalDate.of(2026, 2, 28))

                Then("말일(2/28)로 보정해 생성") {
                    count shouldBe 1
                    verify {
                        entryRepository.save(
                            match { it.entryAt == LocalDateTime.of(2026, 2, 28, 0, 0) },
                        )
                    }
                }
            }
        }
    })
