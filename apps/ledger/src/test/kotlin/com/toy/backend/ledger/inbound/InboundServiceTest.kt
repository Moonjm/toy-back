package com.toy.backend.ledger.inbound

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
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import java.math.BigDecimal

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
            )

        val user = dummyUser()

        beforeTest {
            every { userRepository.findByUsername("testuser") } returns user
            every { inboundRepository.save(any()) } answers { (firstArg() as InboundMessage).withId(100L) }
        }

        Given("승인 문자 수신") {
            When("process") {
                every { entryRepository.save(any()) } answers { (firstArg() as LedgerEntry).withId(11L) }

                val result = service.process("testuser", approvalText)

                Then("EXPENSE 내역 생성, SAVED 로그") {
                    result.status shouldBe InboundStatus.SAVED
                    result.entryId shouldBe 11L
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
                    verify { inboundRepository.save(match { it.status == InboundStatus.SAVED && it.rawText == approvalText }) }
                }
            }
        }

        Given("취소 문자 수신 — 매칭 성공") {
            When("같은 금액·가맹점 승인 건이 7일 내에 있으면") {
                val existing = dummyLedgerEntry(user = user, id = 11L)
                every {
                    entryRepository.findLatestCancellable(
                        user,
                        BigDecimal("18920"),
                        "KRW",
                        "제주특별자치도개발",
                        EntrySource.SMS,
                        any(),
                    )
                } returns existing
                justRun { entryRepository.delete(existing) }

                val result = service.process("testuser", cancelText)

                Then("기존 건 삭제, CANCEL_MATCHED 로그") {
                    result.status shouldBe InboundStatus.CANCEL_MATCHED
                    result.entryId shouldBe 11L
                    verify { entryRepository.delete(existing) }
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
                    )
                } returns null
                every { entryRepository.save(any()) } answers { (firstArg() as LedgerEntry).withId(12L) }

                val result = service.process("testuser", cancelText)

                Then("음수 금액 건으로 저장해 합계 보정") {
                    result.status shouldBe InboundStatus.SAVED
                    verify {
                        entryRepository.save(match { it.amount == BigDecimal("-18920") })
                    }
                }
            }
        }

        Given("파싱 불가 텍스트 수신") {
            When("process") {
                val result = service.process("testuser", "그냥 광고 문자입니다")

                Then("PARSE_FAILED 로그로 원문 보존, 예외 없음") {
                    result.status shouldBe InboundStatus.PARSE_FAILED
                    result.entryId shouldBe null
                    verify { inboundRepository.save(match { it.status == InboundStatus.PARSE_FAILED && it.rawText == "그냥 광고 문자입니다" }) }
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
                )

            When("process") {
                val text = "supports는 통과하지만 parse에서 터지는 문자"
                val result = brokenService.process("testuser", text)

                Then("예외 전파 없이 PARSE_FAILED로 흡수하고 원문을 보존") {
                    result.status shouldBe InboundStatus.PARSE_FAILED
                    result.entryId shouldBe null
                    verify { inboundRepository.save(match { it.status == InboundStatus.PARSE_FAILED && it.rawText == text }) }
                }
            }
        }
    })
