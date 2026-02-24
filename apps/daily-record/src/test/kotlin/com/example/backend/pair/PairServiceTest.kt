package com.example.backend.pair

import com.example.backend.common.constant.ErrorCode
import com.example.backend.common.exception.CustomException
import com.example.backend.dailyrecords.DailyRecordService
import com.example.backend.pair.dto.dummyPairAcceptRequest
import com.example.backend.pair.entity.dummyPairConnection
import com.example.backend.user.UserRepository
import com.example.backend.user.entity.dummyUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify

class PairServiceTest :
    BehaviorSpec({
        val pairRepository = mockk<PairRepository>()
        val userRepository = mockk<UserRepository>()
        val dailyRecordService = mockk<DailyRecordService>()
        val service = PairService(pairRepository, userRepository, dailyRecordService)

        val inviter = dummyUser(username = "inviter", name = "초대자", id = 1L)
        val partner = dummyUser(username = "partner", name = "파트너", id = 2L)

        Given("초대 생성 시") {
            When("정상 요청 (신규)") {
                every { userRepository.findByUsername("inviter") } returns inviter
                every { pairRepository.findByInviterAndStatus(inviter, PairStatus.CONNECTED) } returns null
                every { pairRepository.findByPartnerAndStatus(inviter, PairStatus.CONNECTED) } returns null
                every { pairRepository.findByInviterAndStatus(inviter, PairStatus.PENDING) } returns null
                every { pairRepository.findByInviteCode(any()) } returns null
                every { pairRepository.save(any()) } answers { firstArg() }

                val result = service.createInvite("inviter")

                Then("초대 코드 반환") {
                    result.inviteCode shouldNotBe null
                    result.inviteCode.length shouldBe 6
                }
            }

            When("기존 PENDING 초대 존재") {
                val existing = dummyPairConnection(inviter = inviter, inviteCode = "EXIST1")

                every { userRepository.findByUsername("inviter") } returns inviter
                every { pairRepository.findByInviterAndStatus(inviter, PairStatus.CONNECTED) } returns null
                every { pairRepository.findByPartnerAndStatus(inviter, PairStatus.CONNECTED) } returns null
                every { pairRepository.findByInviterAndStatus(inviter, PairStatus.PENDING) } returns existing

                val result = service.createInvite("inviter")

                Then("기존 초대 코드 반환") {
                    result.inviteCode shouldBe "EXIST1"
                }
            }

            When("이미 연결된 유저") {
                val connected = dummyPairConnection(inviter = inviter, status = PairStatus.CONNECTED)

                every { userRepository.findByUsername("inviter") } returns inviter
                every { pairRepository.findByInviterAndStatus(inviter, PairStatus.CONNECTED) } returns connected

                Then("CustomException(ALREADY_PAIRED) 발생") {
                    val ex = shouldThrow<CustomException> { service.createInvite("inviter") }
                    ex.errorCode shouldBe ErrorCode.ALREADY_PAIRED
                }
            }
        }

        Given("초대 수락 시") {
            When("정상 요청") {
                val pair = dummyPairConnection(inviter = inviter, inviteCode = "ABC123")
                val request = dummyPairAcceptRequest(inviteCode = "ABC123")

                every { userRepository.findByUsername("partner") } returns partner
                every { pairRepository.findByInviterAndStatus(partner, PairStatus.CONNECTED) } returns null
                every { pairRepository.findByPartnerAndStatus(partner, PairStatus.CONNECTED) } returns null
                every { pairRepository.findByInviteCode("ABC123") } returns pair
                every { pairRepository.findByInviterAndStatus(inviter, PairStatus.CONNECTED) } returns null
                every { pairRepository.findByPartnerAndStatus(inviter, PairStatus.CONNECTED) } returns null

                val result = service.acceptInvite("partner", request)

                Then("CONNECTED 상태로 변경") {
                    result.status shouldBe PairStatus.CONNECTED
                    pair.partner shouldBe partner
                }
            }

            When("자기 자신의 초대") {
                val pair = dummyPairConnection(inviter = inviter, inviteCode = "SELF01")
                val request = dummyPairAcceptRequest(inviteCode = "SELF01")

                every { userRepository.findByUsername("inviter") } returns inviter
                every { pairRepository.findByInviterAndStatus(inviter, PairStatus.CONNECTED) } returns null
                every { pairRepository.findByPartnerAndStatus(inviter, PairStatus.CONNECTED) } returns null
                every { pairRepository.findByInviteCode("SELF01") } returns pair

                Then("CustomException(INVALID_REQUEST) 발생") {
                    val ex = shouldThrow<CustomException> { service.acceptInvite("inviter", request) }
                    ex.errorCode shouldBe ErrorCode.INVALID_REQUEST
                }
            }

            When("존재하지 않는 초대 코드") {
                val request = dummyPairAcceptRequest(inviteCode = "NOCODE")

                every { userRepository.findByUsername("partner") } returns partner
                every { pairRepository.findByInviterAndStatus(partner, PairStatus.CONNECTED) } returns null
                every { pairRepository.findByPartnerAndStatus(partner, PairStatus.CONNECTED) } returns null
                every { pairRepository.findByInviteCode("NOCODE") } returns null

                Then("CustomException(RESOURCE_NOT_FOUND) 발생") {
                    val ex = shouldThrow<CustomException> { service.acceptInvite("partner", request) }
                    ex.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
                }
            }
        }

        Given("상태 조회 시") {
            When("연결된 페어 존재") {
                val pair =
                    dummyPairConnection(
                        inviter = inviter,
                        partner = partner,
                        status = PairStatus.CONNECTED,
                    )

                every { userRepository.findByUsername("inviter") } returns inviter
                every {
                    pairRepository.findByInviterAndStatusIn(inviter, listOf(PairStatus.PENDING, PairStatus.CONNECTED))
                } returns pair

                val result = service.getStatus("inviter")

                Then("PairResponse 반환") {
                    result shouldNotBe null
                    result!!.partnerName shouldBe "파트너"
                }
            }

            When("페어 없음") {
                every { userRepository.findByUsername("inviter") } returns inviter
                every {
                    pairRepository.findByInviterAndStatusIn(inviter, listOf(PairStatus.PENDING, PairStatus.CONNECTED))
                } returns null
                every { pairRepository.findByPartnerAndStatus(inviter, PairStatus.CONNECTED) } returns null

                val result = service.getStatus("inviter")

                Then("null 반환") {
                    result shouldBe null
                }
            }
        }

        Given("페어 해제 시") {
            When("정상 요청") {
                val pair = dummyPairConnection(inviter = inviter, status = PairStatus.CONNECTED)

                every { userRepository.findByUsername("inviter") } returns inviter
                every {
                    pairRepository.findByInviterAndStatusIn(inviter, listOf(PairStatus.PENDING, PairStatus.CONNECTED))
                } returns pair
                justRun { pairRepository.delete(pair) }

                service.unpair("inviter")

                Then("delete 호출") {
                    verify { pairRepository.delete(pair) }
                }
            }

            When("페어 없음") {
                every { userRepository.findByUsername("inviter") } returns inviter
                every {
                    pairRepository.findByInviterAndStatusIn(inviter, listOf(PairStatus.PENDING, PairStatus.CONNECTED))
                } returns null
                every { pairRepository.findByPartnerAndStatus(inviter, PairStatus.CONNECTED) } returns null

                Then("CustomException(RESOURCE_NOT_FOUND) 발생") {
                    val ex = shouldThrow<CustomException> { service.unpair("inviter") }
                    ex.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
                }
            }
        }

        Given("파트너 일상기록 조회 시") {
            When("연결된 페어 존재 (inviter가 조회)") {
                val pair =
                    dummyPairConnection(
                        inviter = inviter,
                        partner = partner,
                        status = PairStatus.CONNECTED,
                    )

                every { userRepository.findByUsername("inviter") } returns inviter
                every { pairRepository.findByInviterAndStatus(inviter, PairStatus.CONNECTED) } returns pair
                every { dailyRecordService.list("partner", null, null, null) } returns emptyList()

                val result = service.getPartnerDailyRecords("inviter", null, null, null)

                Then("파트너의 기록 조회 위임") {
                    result shouldBe emptyList()
                    verify { dailyRecordService.list("partner", null, null, null) }
                }
            }

            When("페어 미연결") {
                every { userRepository.findByUsername("inviter") } returns inviter
                every { pairRepository.findByInviterAndStatus(inviter, PairStatus.CONNECTED) } returns null
                every { pairRepository.findByPartnerAndStatus(inviter, PairStatus.CONNECTED) } returns null

                Then("CustomException(PAIR_NOT_CONNECTED) 발생") {
                    val ex =
                        shouldThrow<CustomException> {
                            service.getPartnerDailyRecords("inviter", null, null, null)
                        }
                    ex.errorCode shouldBe ErrorCode.PAIR_NOT_CONNECTED
                }
            }
        }
    })
