package com.toy.backend.ledger.entries

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.entity.withId
import com.toy.backend.common.exception.CustomException
import com.toy.backend.ledger.categories.Category
import com.toy.backend.ledger.categories.CategoryRepository
import com.toy.backend.ledger.dummyLedgerEntry
import com.toy.backend.user.UserRepository
import com.toy.backend.user.entity.dummyUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.springframework.data.repository.findByIdOrNull
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.YearMonth

class LedgerEntryServiceTest :
    BehaviorSpec({
        val repository = mockk<LedgerEntryRepository>()
        val categoryRepository = mockk<CategoryRepository>()
        val userRepository = mockk<UserRepository>()
        val service = LedgerEntryService(repository, categoryRepository, userRepository)

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
            When("분류를 지정하면") {
                val category = Category(user = user, name = "식비").withId(3L)
                val request =
                    LedgerEntryRequest(
                        entryAt = LocalDateTime.of(2026, 7, 19, 12, 0),
                        amount = BigDecimal("5000"),
                        merchant = "카페",
                        categoryId = 3L,
                    )
                every { categoryRepository.findByIdOrNull(3L) } returns category
                every { repository.save(any()) } answers { (firstArg() as LedgerEntry).withId(10L) }

                val id = service.create("testuser", request)

                Then("저장된 ID 반환, source는 MANUAL, 분류 연결") {
                    id shouldBe 10L
                    verify {
                        repository.save(
                            match {
                                it.source == EntrySource.MANUAL &&
                                    it.amount == BigDecimal("5000") &&
                                    it.category?.name == "식비"
                            },
                        )
                    }
                }
            }

            When("타인 소유 분류를 지정하면") {
                val other = dummyUser(username = "other", id = 2L)
                every { categoryRepository.findByIdOrNull(9L) } returns Category(user = other, name = "남의분류").withId(9L)

                Then("RESOURCE_NOT_FOUND 예외") {
                    val e =
                        shouldThrow<CustomException> {
                            service.create(
                                "testuser",
                                LedgerEntryRequest(
                                    entryAt = LocalDateTime.of(2026, 7, 19, 12, 0),
                                    amount = BigDecimal("5000"),
                                    categoryId = 9L,
                                ),
                            )
                        }
                    e.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
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
