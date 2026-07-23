package com.toy.backend.ledger.inbound

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.entity.withId
import com.toy.backend.common.exception.CustomException
import com.toy.backend.ledger.LedgerErrorCode
import com.toy.backend.user.UserRepository
import com.toy.backend.user.entity.dummyUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify

class InboundFailureServiceTest :
    BehaviorSpec({
        val repository = mockk<InboundMessageRepository>()
        val userRepository = mockk<UserRepository>()
        val service = InboundFailureService(repository, userRepository)

        val user = dummyUser()

        beforeTest {
            every { userRepository.findByUsername("testuser") } returns user
        }

        Given("파싱 실패 기록이 있으면") {
            val failed =
                InboundMessage(user = user, rawText = "알 수 없는 형식의 문자", status = InboundStatus.PARSE_FAILED)
                    .withId(3L)
            every {
                repository.findAllByUserAndStatusOrderByIdDesc(user, InboundStatus.PARSE_FAILED)
            } returns listOf(failed)

            When("실패 목록 조회") {
                val result = service.failures("testuser")

                Then("원문과 수신 시각을 반환한다") {
                    result.size shouldBe 1
                    result[0].id shouldBe 3L
                    result[0].rawText shouldBe "알 수 없는 형식의 문자"
                    result[0].receivedAt shouldBe failed.createdAt
                }
            }
        }

        Given("실패 기록 삭제") {
            When("PARSE_FAILED 건이면") {
                val failed =
                    InboundMessage(user = user, rawText = "광고 문자", status = InboundStatus.PARSE_FAILED)
                        .withId(4L)
                every { repository.findByIdAndUser(4L, user) } returns failed
                justRun { repository.delete(failed) }

                service.delete("testuser", 4L)

                Then("삭제된다") {
                    verify { repository.delete(failed) }
                }
            }

            When("이미 처리된(SAVED) 건이면") {
                val saved =
                    InboundMessage(user = user, rawText = "정상 승인 문자", status = InboundStatus.SAVED, entryId = 9L)
                        .withId(5L)
                every { repository.findByIdAndUser(5L, user) } returns saved

                Then("처리 이력 보호를 위해 INBOUND_NOT_DELETABLE") {
                    val exception =
                        shouldThrow<CustomException> {
                            service.delete("testuser", 5L)
                        }
                    exception.errorCode shouldBe LedgerErrorCode.INBOUND_NOT_DELETABLE
                }
            }

            When("소유하지 않았거나 없는 건이면") {
                every { repository.findByIdAndUser(99L, user) } returns null

                Then("RESOURCE_NOT_FOUND") {
                    val exception =
                        shouldThrow<CustomException> {
                            service.delete("testuser", 99L)
                        }
                    exception.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
                }
            }
        }
    })
