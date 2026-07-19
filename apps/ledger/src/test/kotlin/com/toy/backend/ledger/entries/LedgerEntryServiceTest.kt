package com.toy.backend.ledger.entries

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.entity.withId
import com.toy.backend.common.exception.CustomException
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
import java.time.LocalDate
import java.time.LocalDateTime

class LedgerEntryServiceTest :
    BehaviorSpec({
        val repository = mockk<LedgerEntryRepository>()
        val userRepository = mockk<UserRepository>()
        val service = LedgerEntryService(repository, userRepository)

        val user = dummyUser()

        beforeTest {
            every { userRepository.findByUsername("testuser") } returns user
        }

        Given("내역 목록 조회") {
            When("기간으로 조회") {
                val from = LocalDate.of(2026, 7, 1)
                val to = LocalDate.of(2026, 7, 31)
                every {
                    repository.findAllByUserAndEntryAtGreaterThanEqualAndEntryAtLessThanOrderByEntryAtDesc(
                        user,
                        from.atStartOfDay(),
                        to.plusDays(1).atStartOfDay(),
                    )
                } returns listOf(dummyLedgerEntry(user = user))

                val result = service.list("testuser", from, to)

                Then("응답 DTO 리스트 반환") {
                    result.size shouldBe 1
                    result[0].merchant shouldBe "제주특별자치도개발"
                    result[0].amount shouldBe BigDecimal("18920")
                }
            }
        }

        Given("내역 생성") {
            When("정상 요청") {
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
                            match { it.source == EntrySource.MANUAL && it.amount == BigDecimal("5000") },
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
