package com.toy.backend.ledger.recurring

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.entity.withId
import com.toy.backend.common.exception.CustomException
import com.toy.backend.ledger.dummyLedgerEntry
import com.toy.backend.ledger.entries.EntrySource
import com.toy.backend.ledger.entries.EntryType
import com.toy.backend.ledger.entries.LedgerEntry
import com.toy.backend.ledger.entries.LedgerEntryRepository
import com.toy.backend.user.UserRepository
import com.toy.backend.user.entity.dummyUser
import io.kotest.assertions.throwables.shouldThrow
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
    merchant: String? = "데이트비용",
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
        merchant = merchant,
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
                every { repository.findAllByUser(user) } returns emptyList()
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
                every { repository.findAllByUser(user) } returns emptyList()
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

            When("같은 금액·가맹점·날짜의 활성 규칙이 이미 있으면") {
                val entry =
                    dummyLedgerEntry(
                        user = user,
                        entryAt = LocalDateTime.of(2026, 7, 25, 0, 0),
                        merchant = "넷플릭스",
                        id = 7L,
                    )
                every { entryRepository.findByIdOrNull(7L) } returns entry
                every { repository.findAllByUser(user) } returns
                    listOf(dummyRule(dayOfMonth = 25, amount = BigDecimal("18920"), merchant = "넷플릭스"))

                Then("중복 예외 — 매달 같은 내역이 2건씩 생기는 것을 막는다") {
                    val exception =
                        shouldThrow<CustomException> {
                            service.create("testuser", RecurringRuleCreateRequest(entryId = 7L))
                        }
                    exception.errorCode shouldBe ErrorCode.DUPLICATE_RESOURCE
                }
            }

            When("같은 금액·가맹점이라도 날짜가 다르면") {
                val entry =
                    dummyLedgerEntry(
                        user = user,
                        entryAt = LocalDateTime.of(2026, 7, 25, 0, 0),
                        merchant = "넷플릭스",
                        id = 8L,
                    )
                every { entryRepository.findByIdOrNull(8L) } returns entry
                every { repository.findAllByUser(user) } returns
                    listOf(dummyRule(dayOfMonth = 10, amount = BigDecimal("18920"), merchant = "넷플릭스"))
                every { repository.save(any()) } answers { (firstArg() as RecurringRule).withId(9L) }

                Then("별개 규칙으로 등록된다 (매월 1일·15일 같은 금액 케이스)") {
                    service.create("testuser", RecurringRuleCreateRequest(entryId = 8L)) shouldBe 9L
                }
            }

            When("같은 조건이라도 비활성 규칙뿐이면") {
                val entry =
                    dummyLedgerEntry(
                        user = user,
                        entryAt = LocalDateTime.of(2026, 7, 25, 0, 0),
                        merchant = "넷플릭스",
                        id = 10L,
                    )
                every { entryRepository.findByIdOrNull(10L) } returns entry
                every { repository.findAllByUser(user) } returns
                    listOf(dummyRule(dayOfMonth = 25, amount = BigDecimal("18920"), merchant = "넷플릭스", active = false))
                every { repository.save(any()) } answers { (firstArg() as RecurringRule).withId(11L) }

                Then("새 규칙으로 등록된다") {
                    service.create("testuser", RecurringRuleCreateRequest(entryId = 10L)) shouldBe 11L
                }
            }
        }

        Given("규칙 수정") {
            When("다른 활성 규칙과 같은 값으로 수정하면") {
                val rule = dummyRule(user = user, dayOfMonth = 10, amount = BigDecimal("10000"), merchant = "유튜브", id = 30L)
                val other = dummyRule(user = user, dayOfMonth = 25, amount = BigDecimal("18920"), merchant = "넷플릭스", id = 31L)
                every { repository.findByIdOrNull(30L) } returns rule
                every { repository.findAllByUser(user) } returns listOf(rule, other)

                Then("중복 예외 — 수정 경로로도 같은 내역이 2건씩 생기는 것을 막는다") {
                    val exception =
                        shouldThrow<CustomException> {
                            service.update(
                                "testuser",
                                30L,
                                RecurringRuleUpdateRequest(
                                    dayOfMonth = 25,
                                    amount = BigDecimal("18920"),
                                    currency = "KRW",
                                    type = EntryType.EXPENSE,
                                    merchant = "넷플릭스",
                                    active = true,
                                ),
                            )
                        }
                    exception.errorCode shouldBe ErrorCode.DUPLICATE_RESOURCE
                }
            }

            When("자기 자신과 같은 값으로 수정하면 (자기 제외)") {
                val rule = dummyRule(user = user, dayOfMonth = 25, amount = BigDecimal("18920"), merchant = "넷플릭스", id = 32L)
                every { repository.findByIdOrNull(32L) } returns rule
                every { repository.findAllByUser(user) } returns listOf(rule)

                service.update(
                    "testuser",
                    32L,
                    RecurringRuleUpdateRequest(
                        dayOfMonth = 25,
                        amount = BigDecimal("18920"),
                        currency = "KRW",
                        type = EntryType.EXPENSE,
                        merchant = "넷플릭스",
                        description = "요금제 변경",
                        active = true,
                    ),
                )

                Then("자기 자신은 중복으로 보지 않고 수정된다") {
                    rule.description shouldBe "요금제 변경"
                }
            }

            When("다른 활성 규칙과 값이 겹쳐도 비활성으로 수정하면") {
                val rule = dummyRule(user = user, dayOfMonth = 25, amount = BigDecimal("18920"), merchant = "넷플릭스", id = 33L)
                val other = dummyRule(user = user, dayOfMonth = 25, amount = BigDecimal("18920"), merchant = "넷플릭스", id = 34L)
                every { repository.findByIdOrNull(33L) } returns rule
                every { repository.findAllByUser(user) } returns listOf(rule, other)

                service.update(
                    "testuser",
                    33L,
                    RecurringRuleUpdateRequest(
                        dayOfMonth = 25,
                        amount = BigDecimal("18920"),
                        currency = "KRW",
                        type = EntryType.EXPENSE,
                        merchant = "넷플릭스",
                        active = false,
                    ),
                )

                Then("비활성 규칙은 생성 대상이 아니므로 허용된다") {
                    rule.active shouldBe false
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
