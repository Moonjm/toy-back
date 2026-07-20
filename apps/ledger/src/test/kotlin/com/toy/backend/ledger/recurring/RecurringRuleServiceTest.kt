package com.toy.backend.ledger.recurring

import com.toy.backend.common.entity.withId
import com.toy.backend.ledger.categories.CategoryRepository
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
        val categoryRepository = mockk<CategoryRepository>()
        val userRepository = mockk<UserRepository>()
        val service = RecurringRuleService(repository, entryRepository, categoryRepository, userRepository)

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

                Then("entry 값 복사, 반복일은 entry 날짜의 일, entry의 달을 생성 완료로 초기화(원본 중복 방지)") {
                    id shouldBe 2L
                    verify {
                        repository.save(
                            match {
                                it.dayOfMonth == 25 &&
                                    it.amount == BigDecimal("18920") &&
                                    it.merchant == "넷플릭스" &&
                                    it.type == EntryType.EXPENSE &&
                                    it.lastGeneratedMonth == "2026-07"
                            },
                        )
                    }
                }
            }

            When("과거 달의 entry로 규칙을 만들면") {
                val entry =
                    dummyLedgerEntry(
                        user = user,
                        entryAt = LocalDateTime.of(2026, 6, 25, 0, 0),
                        merchant = "넷플릭스",
                        id = 6L,
                    )
                every { entryRepository.findByIdOrNull(6L) } returns entry
                every { repository.save(any()) } answers { (firstArg() as RecurringRule).withId(3L) }

                service.create("testuser", RecurringRuleCreateRequest(entryId = 6L))

                Then("lastGeneratedMonth는 entry의 달 — 이번 달 발생분은 캐치업으로 생성된다") {
                    verify {
                        repository.save(
                            match { it.dayOfMonth == 25 && it.lastGeneratedMonth == "2026-06" },
                        )
                    }
                }
            }
        }

        Given("스케줄러 생성 — findDueRuleIds / generateForRule") {
            When("반복일이 지났고 이번 달 미생성이면") {
                val rule = dummyRule(dayOfMonth = 25, lastGeneratedMonth = "2026-06", id = 1L)
                every { repository.findAllByActiveTrue() } returns listOf(rule)
                every { repository.findByIdOrNull(1L) } returns rule
                every { entryRepository.save(any()) } answers { (firstArg() as LedgerEntry).withId(20L) }

                val dueIds = service.findDueRuleIds(LocalDate.of(2026, 7, 26))
                service.generateForRule(1L, LocalDate.of(2026, 7, 26))

                Then("findDueRuleIds가 해당 id 반환, entry 생성(source=RECURRING), lastGeneratedMonth 갱신") {
                    dueIds shouldBe listOf(1L)
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
                val rule = dummyRule(dayOfMonth = 25, lastGeneratedMonth = "2026-07", id = 2L)
                every { repository.findAllByActiveTrue() } returns listOf(rule)

                val dueIds = service.findDueRuleIds(LocalDate.of(2026, 7, 26))

                Then("빈 목록") { dueIds shouldBe emptyList() }
            }

            When("반복일이 아직 안 됐으면") {
                val rule = dummyRule(dayOfMonth = 25, lastGeneratedMonth = "2026-06", id = 3L)
                every { repository.findAllByActiveTrue() } returns listOf(rule)

                val dueIds = service.findDueRuleIds(LocalDate.of(2026, 7, 10))

                Then("빈 목록") { dueIds shouldBe emptyList() }
            }

            When("31일 규칙을 2월에 실행하면") {
                val rule = dummyRule(dayOfMonth = 31, lastGeneratedMonth = "2026-01", id = 4L)
                every { repository.findAllByActiveTrue() } returns listOf(rule)
                every { repository.findByIdOrNull(4L) } returns rule
                every { entryRepository.save(any()) } answers { (firstArg() as LedgerEntry).withId(21L) }

                val dueIds = service.findDueRuleIds(LocalDate.of(2026, 2, 28))
                service.generateForRule(4L, LocalDate.of(2026, 2, 28))

                Then("말일(2/28)로 보정해 생성") {
                    dueIds shouldBe listOf(4L)
                    verify {
                        entryRepository.save(
                            match { it.entryAt == LocalDateTime.of(2026, 2, 28, 0, 0) },
                        )
                    }
                }
            }
        }
    })
