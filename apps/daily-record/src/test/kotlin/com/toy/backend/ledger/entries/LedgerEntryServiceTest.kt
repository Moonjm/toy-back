package com.toy.backend.ledger.entries

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.entity.withId
import com.toy.backend.common.exception.CustomException
import com.toy.backend.ledger.dummyLedgerEntry
import com.toy.backend.ledger.inbound.FxMemo
import com.toy.backend.ledger.inbound.FxRateClient
import com.toy.backend.user.UserRepository
import com.toy.backend.user.entity.dummyUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.springframework.data.repository.findByIdOrNull
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

class LedgerEntryServiceTest :
    BehaviorSpec({
        val repository = mockk<LedgerEntryRepository>()
        val userRepository = mockk<UserRepository>()
        val fxRateClient = mockk<FxRateClient>()
        val service = LedgerEntryService(repository, userRepository, fxRateClient)

        val user = dummyUser()

        beforeTest {
            every { userRepository.findByUsername("testuser") } returns user
        }

        Given("내역 목록 조회") {
            When("연월로 조회") {
                every {
                    repository.search(
                        user,
                        LocalDateTime.of(2026, 7, 1, 0, 0),
                        LocalDateTime.of(2026, 8, 1, 0, 0),
                        null,
                    )
                } returns listOf(dummyLedgerEntry(user = user))

                val result = service.list("testuser", YearMonth.of(2026, 7), null)

                Then("해당 연월 구간으로 조회한 결과를 반환") {
                    result.size shouldBe 1
                    result[0].merchant shouldBe "제주특별자치도개발"
                    result[0].amount shouldBe BigDecimal("18920")
                }
            }

            When("검색어만으로 조회") {
                every { repository.search(user, null, null, "카카오") } returns emptyList()

                service.list("testuser", null, "카카오")

                Then("기간 조건 없이 검색어로만 조회") {
                    verify { repository.search(user, null, null, "카카오") }
                }
            }

            When("연월·검색어가 모두 없으면") {
                Then("INVALID_REQUEST 예외 — 전체 조회를 막는다") {
                    val e = shouldThrow<CustomException> { service.list("testuser", null, "  ") }
                    e.errorCode shouldBe ErrorCode.INVALID_REQUEST
                }
            }
        }

        Given("내역 생성") {
            When("요청으로 생성하면") {
                val request =
                    LedgerEntryRequest(
                        entryAt = LocalDateTime.of(2026, 7, 19, 12, 0),
                        amount = BigDecimal("5000"),
                        merchant = "카페",
                    )
                every { repository.save(any()) } answers { (firstArg() as LedgerEntry).withId(10L) }

                val id = service.create("testuser", request)

                Then("저장된 ID 반환, source는 MANUAL") {
                    id shouldBe 10L
                    verify {
                        repository.save(
                            match {
                                it.source == EntrySource.MANUAL && it.amount == BigDecimal("5000")
                            },
                        )
                    }
                }
            }
        }

        Given("내역 수정") {
            When("타인 소유 내역이면") {
                val other = dummyUser(username = "other", id = 2L)
                val entry = dummyLedgerEntry(user = other, id = 5L)
                every { repository.findByIdOrNull(5L) } returns entry

                Then("RESOURCE_NOT_FOUND 예외") {
                    val e =
                        shouldThrow<CustomException> {
                            service.update(
                                "testuser",
                                5L,
                                LedgerEntryRequest(
                                    entryAt = LocalDateTime.of(2026, 7, 19, 12, 0),
                                    amount = BigDecimal("1000"),
                                ),
                            )
                        }
                    e.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
                }
            }

            When("본인 소유 내역이면") {
                val entry = dummyLedgerEntry(user = user, id = 6L)
                every { repository.findByIdOrNull(6L) } returns entry

                service.update(
                    "testuser",
                    6L,
                    LedgerEntryRequest(
                        entryAt = LocalDateTime.of(2026, 7, 20, 9, 0),
                        amount = BigDecimal("7000"),
                        merchant = "편의점",
                        description = "간식",
                    ),
                )

                Then("필드가 요청 값으로 갱신된다") {
                    entry.entryAt shouldBe LocalDateTime.of(2026, 7, 20, 9, 0)
                    entry.amount shouldBe BigDecimal("7000")
                    entry.merchant shouldBe "편의점"
                    entry.description shouldBe "간식"
                }
            }
        }

        Given("외화 내역 수정 — 환율 메모 갱신") {
            When("날짜가 바뀌면") {
                val entry =
                    dummyLedgerEntry(
                        user = user,
                        entryAt = LocalDateTime.of(2026, 7, 10, 9, 0),
                        currency = "JPY",
                        description = "일시불 · 환율 1 JPY ≈ 9.15원 (약 9,150원)",
                        id = 8L,
                    )
                every { repository.findByIdOrNull(8L) } returns entry
                every { fxRateClient.rateToKrw("JPY", LocalDate.of(2026, 7, 21)) } returns BigDecimal("9.0914")

                service.update(
                    "testuser",
                    8L,
                    LedgerEntryRequest(
                        entryAt = LocalDateTime.of(2026, 7, 21, 9, 0),
                        amount = BigDecimal("1000"),
                        currency = "JPY",
                        description = "일시불 · 환율 1 JPY ≈ 9.15원 (약 9,150원)",
                    ),
                )

                Then("낡은 메모를 걷어내고 새 날짜 고시환율로 다시 만든다") {
                    entry.description shouldBe "일시불 · 환율 1 JPY ≈ 9.09원 (약 9,091원)"
                }
            }

            When("시각만 바뀌고 날짜는 그대로면") {
                val entry =
                    dummyLedgerEntry(
                        user = user,
                        entryAt = LocalDateTime.of(2026, 7, 10, 9, 0),
                        currency = "JPY",
                        description = "환율 1 JPY ≈ 9.15원 (약 9,150원)",
                        id = 9L,
                    )
                every { repository.findByIdOrNull(9L) } returns entry

                service.update(
                    "testuser",
                    9L,
                    LedgerEntryRequest(
                        entryAt = LocalDateTime.of(2026, 7, 10, 21, 0),
                        amount = BigDecimal("1000"),
                        currency = "JPY",
                        description = "환율 1 JPY ≈ 9.15원 (약 9,150원)",
                    ),
                )

                Then("환율을 조회하지 않고 메모를 유지한다") {
                    entry.description shouldBe "환율 1 JPY ≈ 9.15원 (약 9,150원)"
                    verify(exactly = 0) { fxRateClient.rateToKrw(any(), any()) }
                }
            }

            When("날짜가 바뀌었지만 환율 조회가 실패하면") {
                val entry =
                    dummyLedgerEntry(
                        user = user,
                        entryAt = LocalDateTime.of(2026, 7, 10, 9, 0),
                        currency = "JPY",
                        description = "환율 1 JPY ≈ 9.15원 (약 9,150원)",
                        id = 10L,
                    )
                every { repository.findByIdOrNull(10L) } returns entry
                every { fxRateClient.rateToKrw("JPY", LocalDate.of(2026, 7, 21)) } returns null

                service.update(
                    "testuser",
                    10L,
                    LedgerEntryRequest(
                        entryAt = LocalDateTime.of(2026, 7, 21, 9, 0),
                        amount = BigDecimal("1000"),
                        currency = "JPY",
                        description = "환율 1 JPY ≈ 9.15원 (약 9,150원)",
                    ),
                )

                Then("옛 날짜 기준의 틀린 메모만 제거한다") {
                    entry.description shouldBe null
                }
            }

            When("원화 내역이면 날짜가 바뀌어도") {
                val entry =
                    dummyLedgerEntry(
                        user = user,
                        entryAt = LocalDateTime.of(2026, 7, 10, 9, 0),
                        description = "간식",
                        id = 11L,
                    )
                every { repository.findByIdOrNull(11L) } returns entry

                service.update(
                    "testuser",
                    11L,
                    LedgerEntryRequest(
                        entryAt = LocalDateTime.of(2026, 7, 21, 9, 0),
                        amount = BigDecimal("1000"),
                        description = "간식",
                    ),
                )

                Then("환율을 조회하지 않고 내용 그대로 저장한다") {
                    entry.description shouldBe "간식"
                    verify(exactly = 0) { fxRateClient.rateToKrw(any(), any()) }
                }
            }

            When("기본 메모가 길이 제한(500자)에 가까우면") {
                val longBase = "가".repeat(490)
                val entry =
                    dummyLedgerEntry(
                        user = user,
                        entryAt = LocalDateTime.of(2026, 7, 10, 9, 0),
                        currency = "JPY",
                        description = longBase,
                        id = 12L,
                    )
                every { repository.findByIdOrNull(12L) } returns entry
                every { fxRateClient.rateToKrw("JPY", LocalDate.of(2026, 7, 21)) } returns BigDecimal("9.0914")

                service.update(
                    "testuser",
                    12L,
                    LedgerEntryRequest(
                        entryAt = LocalDateTime.of(2026, 7, 21, 9, 0),
                        amount = BigDecimal("1000"),
                        currency = "JPY",
                        description = longBase,
                    ),
                )

                Then("환율 메모가 잘리지 않게 기본 메모를 먼저 줄인다") {
                    val description = entry.description.shouldNotBeNull()
                    description.length shouldBe 500
                    description.endsWith("환율 1 JPY ≈ 9.09원 (약 9,091원)") shouldBe true
                    // 메모가 온전해야 다음 수정 때 strip이 인식해 낡은 조각이 남지 않는다.
                    // 500 - 메모(27자) - 구분자(3자) = 470자
                    FxMemo.strip(description) shouldBe "가".repeat(470)
                }
            }
        }

        Given("내역 삭제") {
            When("본인 소유 내역이면") {
                val entry = dummyLedgerEntry(user = user, id = 7L)
                every { repository.findByIdOrNull(7L) } returns entry
                justRun { repository.delete(entry) }

                service.delete("testuser", 7L)

                Then("삭제 호출") {
                    verify { repository.delete(entry) }
                }
            }
        }
    })
