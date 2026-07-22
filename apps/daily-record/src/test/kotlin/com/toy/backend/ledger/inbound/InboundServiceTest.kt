package com.toy.backend.ledger.inbound

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.entity.withId
import com.toy.backend.common.exception.CustomException
import com.toy.backend.ledger.LedgerErrorCode
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
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.LocalDateTime

private val approvalText =
    """
    [Web발신]
    대한항공카드 승인
    문*민
    18,920원 일시불
    07/14 07:38
    제주특별자치도개발
    누적438,919원
    """.trimIndent()

private val cancelText = approvalText.replace("승인", "취소")

/** 단위 테스트용 no-op 트랜잭션 매니저 — 콜백을 그대로 실행하고 예외는 전파한다. */
private val noopTransactionManager =
    object : PlatformTransactionManager {
        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()

        override fun commit(status: TransactionStatus) {}

        override fun rollback(status: TransactionStatus) {}
    }

class InboundServiceTest :
    BehaviorSpec({
        val entryRepository = mockk<LedgerEntryRepository>()
        val inboundRepository = mockk<InboundMessageRepository>()
        val userRepository = mockk<UserRepository>()
        val service =
            InboundService(
                parsers = listOf(CardApprovalParser(), OverseasApprovalParser(), KakaoPayParser()),
                entryRepository = entryRepository,
                inboundRepository = inboundRepository,
                userRepository = userRepository,
                transactionTemplate = TransactionTemplate(noopTransactionManager),
            )

        val user = dummyUser()

        beforeTest {
            every { userRepository.findByUsername("testuser") } returns user
            every { inboundRepository.save(any()) } answers { (firstArg() as InboundMessage).withId(100L) }
        }

        Given("승인 문자 수신") {
            When("process") {
                every { entryRepository.save(any()) } answers { (firstArg() as LedgerEntry).withId(11L) }

                val inboundId = service.process("testuser", approvalText)

                Then("EXPENSE 내역 생성, SAVED 로그, 수신 기록 id 반환") {
                    inboundId shouldBe 100L
                    verify {
                        entryRepository.save(
                            match {
                                it.amount == BigDecimal("18920") &&
                                    it.type == EntryType.EXPENSE &&
                                    it.source == EntrySource.SMS &&
                                    it.merchant == "제주특별자치도개발"
                            },
                        )
                    }
                    verify {
                        inboundRepository.save(
                            match { it.status == InboundStatus.SAVED && it.entryId == 11L && it.rawText == approvalText },
                        )
                    }
                }
            }
        }

        Given("취소 문자 수신 — 매칭 성공") {
            When("같은 금액·가맹점 승인 건이 7일 내에 있으면") {
                val existing = dummyLedgerEntry(user = user, id = 11L)
                val afterSlot = slot<LocalDateTime>()
                val beforeSlot = slot<LocalDateTime>()
                every {
                    entryRepository.findLatestCancellable(
                        user,
                        BigDecimal("18920"),
                        "KRW",
                        "제주특별자치도개발",
                        EntrySource.SMS,
                        capture(afterSlot),
                        capture(beforeSlot),
                    )
                } returns existing
                justRun { entryRepository.delete(existing) }

                val inboundId = service.process("testuser", cancelText)

                Then("기존 건 삭제, CANCEL_MATCHED 로그, 매칭 창은 파싱된 취소 시각(07/14 07:38) 기준 직전 7일") {
                    inboundId shouldBe 100L
                    verify { entryRepository.delete(existing) }
                    verify { inboundRepository.save(match { it.status == InboundStatus.CANCEL_MATCHED && it.entryId == 11L }) }
                    beforeSlot.captured.monthValue shouldBe 7
                    beforeSlot.captured.dayOfMonth shouldBe 14
                    beforeSlot.captured.hour shouldBe 7
                    beforeSlot.captured.minute shouldBe 38
                    afterSlot.captured shouldBe beforeSlot.captured.minusDays(7)
                }
            }
        }

        Given("취소 문자 수신 — 매칭 실패") {
            When("매칭되는 승인 건이 없으면") {
                every {
                    entryRepository.findLatestCancellable(
                        user,
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                    )
                } returns null
                every { entryRepository.save(any()) } answers { (firstArg() as LedgerEntry).withId(12L) }

                service.process("testuser", cancelText)

                Then("음수 금액 건으로 저장해 합계 보정") {
                    verify {
                        entryRepository.save(match { it.amount == BigDecimal("-18920") })
                    }
                    verify { inboundRepository.save(match { it.status == InboundStatus.SAVED && it.entryId == 12L }) }
                }
            }
        }

        Given("파싱 불가 텍스트 수신") {
            When("process") {
                val inboundId = service.process("testuser", "그냥 광고 문자입니다")

                Then("PARSE_FAILED 로그로 원문 보존, 재처리용 수신 기록 id 반환") {
                    inboundId shouldBe 100L
                    verify {
                        inboundRepository.save(
                            match { it.status == InboundStatus.PARSE_FAILED && it.entryId == null && it.rawText == "그냥 광고 문자입니다" },
                        )
                    }
                }
            }
        }

        Given("supports는 true지만 parse가 예외를 던지는 파서") {
            val brokenParser =
                mockk<MessageParser> {
                    every { supports(any()) } returns true
                    every { parse(any(), any()) } throws RuntimeException("파서 결함")
                }
            val brokenService =
                InboundService(
                    parsers = listOf(brokenParser),
                    entryRepository = entryRepository,
                    inboundRepository = inboundRepository,
                    userRepository = userRepository,
                    transactionTemplate = TransactionTemplate(noopTransactionManager),
                )

            When("process") {
                val text = "supports는 통과하지만 parse에서 터지는 문자"
                val inboundId = brokenService.process("testuser", text)

                Then("예외 전파 없이 PARSE_FAILED로 흡수하고 원문을 보존") {
                    inboundId shouldBe 100L
                    verify { inboundRepository.save(match { it.status == InboundStatus.PARSE_FAILED && it.rawText == text }) }
                }
            }
        }

        Given("파싱은 성공했지만 내역 저장이 실패하는 경우") {
            When("process") {
                every { entryRepository.save(any()) } throws RuntimeException("DB 저장 실패")

                val inboundId = service.process("testuser", approvalText)

                Then("PARSE_FAILED로 원문 보존 — 문자는 재발송이 안 되므로 이후 재처리 가능해야 함") {
                    inboundId shouldBe 100L
                    verify { inboundRepository.save(match { it.status == InboundStatus.PARSE_FAILED && it.rawText == approvalText }) }
                }
            }
        }

        Given("실패 건 재처리(retry)") {
            When("PARSE_FAILED 건을 재처리해 성공하면") {
                val message =
                    InboundMessage(
                        user = user,
                        rawText = approvalText,
                        status = InboundStatus.PARSE_FAILED,
                    ).withId(100L)
                every { inboundRepository.findByIdAndUser(100L, user) } returns message
                every { entryRepository.save(any()) } answers { (firstArg() as LedgerEntry).withId(11L) }

                service.retry("testuser", 100L)

                Then("내역이 생성되고 기존 로그의 상태가 SAVED로 갱신된다") {
                    verify {
                        inboundRepository.save(
                            match { it.requiredId == 100L && it.status == InboundStatus.SAVED && it.entryId == 11L },
                        )
                    }
                }
            }

            When("재처리해도 파싱이 실패하면") {
                val message =
                    InboundMessage(
                        user = user,
                        rawText = "여전히 파싱 불가능한 원문",
                        status = InboundStatus.PARSE_FAILED,
                    ).withId(103L)
                every { inboundRepository.findByIdAndUser(103L, user) } returns message

                Then("MESSAGE_PARSE_FAILED 예외(400), 로그 상태는 PARSE_FAILED 유지") {
                    val e = shouldThrow<CustomException> { service.retry("testuser", 103L) }
                    e.errorCode shouldBe LedgerErrorCode.MESSAGE_PARSE_FAILED
                    message.status shouldBe InboundStatus.PARSE_FAILED
                }
            }

            When("PARSE_FAILED가 아닌 건이면") {
                val message =
                    InboundMessage(
                        user = user,
                        rawText = approvalText,
                        status = InboundStatus.SAVED,
                        entryId = 11L,
                    ).withId(101L)
                every { inboundRepository.findByIdAndUser(101L, user) } returns message

                Then("INBOUND_NOT_RETRYABLE 예외") {
                    val e = shouldThrow<CustomException> { service.retry("testuser", 101L) }
                    e.errorCode shouldBe LedgerErrorCode.INBOUND_NOT_RETRYABLE
                }
            }

            When("타인 소유 건이면") {
                // 소유자를 조건에 넣어 조회하므로 타인 건은 애초에 나오지 않는다.
                every { inboundRepository.findByIdAndUser(102L, user) } returns null

                Then("RESOURCE_NOT_FOUND 예외 (존재 숨김)") {
                    val e = shouldThrow<CustomException> { service.retry("testuser", 102L) }
                    e.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
                }
            }
        }
    })
