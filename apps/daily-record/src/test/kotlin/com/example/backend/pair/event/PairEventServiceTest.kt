package com.example.backend.pair.event

import com.example.backend.common.constant.ErrorCode
import com.example.backend.common.exception.CustomException
import com.example.backend.pair.PairService
import com.example.backend.pair.PairStatus
import com.example.backend.pair.dto.dummyPairEventRequest
import com.example.backend.pair.entity.dummyPairConnection
import com.example.backend.pair.entity.dummyPairEvent
import com.example.backend.user.entity.dummyUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.springframework.data.repository.findByIdOrNull

class PairEventServiceTest :
    BehaviorSpec({
        val pairEventRepository = mockk<PairEventRepository>()
        val pairService = mockk<PairService>()
        val service = PairEventService(pairEventRepository, pairService)

        val inviter = dummyUser(username = "inviter", name = "초대자", id = 1L)
        val partner = dummyUser(username = "partner", name = "파트너", id = 2L)
        val pair =
            dummyPairConnection(
                inviter = inviter,
                partner = partner,
                status = PairStatus.CONNECTED,
            )

        Given("이벤트 목록 조회 시") {
            When("연결된 페어, 필터 없음") {
                val event = dummyPairEvent(pair = pair)

                every { pairService.findUser("inviter") } returns inviter
                every { pairService.findConnectedPair(inviter) } returns pair
                every { pairEventRepository.findByPairOrderByEventDate(pair) } returns listOf(event)

                val result = service.list("inviter", null, null)

                Then("전체 이벤트 반환") {
                    result.size shouldBe 1
                    result[0].title shouldBe "기념일"
                }
            }

            When("페어 미연결") {
                every { pairService.findUser("inviter") } returns inviter
                every { pairService.findConnectedPair(inviter) } returns null

                Then("CustomException(PAIR_NOT_CONNECTED) 발생") {
                    val ex = shouldThrow<CustomException> { service.list("inviter", null, null) }
                    ex.errorCode shouldBe ErrorCode.PAIR_NOT_CONNECTED
                }
            }
        }

        Given("이벤트 생성 시") {
            When("정상 요청") {
                val request = dummyPairEventRequest()
                val savedEvent = dummyPairEvent(pair = pair)

                every { pairService.findUser("inviter") } returns inviter
                every { pairService.findConnectedPair(inviter) } returns pair
                every { pairEventRepository.save(any()) } returns savedEvent

                val result = service.create("inviter", request)

                Then("저장 후 ID 반환") {
                    result shouldBe 1L
                }
            }

            When("페어 미연결") {
                every { pairService.findUser("inviter") } returns inviter
                every { pairService.findConnectedPair(inviter) } returns null

                Then("CustomException(PAIR_NOT_CONNECTED) 발생") {
                    val ex =
                        shouldThrow<CustomException> {
                            service.create("inviter", dummyPairEventRequest())
                        }
                    ex.errorCode shouldBe ErrorCode.PAIR_NOT_CONNECTED
                }
            }
        }

        Given("이벤트 삭제 시") {
            When("정상 요청") {
                val event = dummyPairEvent(pair = pair)

                every { pairService.findUser("inviter") } returns inviter
                every { pairService.findConnectedPair(inviter) } returns pair
                every { pairEventRepository.findByIdOrNull(1L) } returns event
                justRun { pairEventRepository.delete(event) }

                service.delete("inviter", 1L)

                Then("delete 호출") {
                    verify { pairEventRepository.delete(event) }
                }
            }

            When("다른 페어의 이벤트") {
                val otherPair = dummyPairConnection(id = 99L)
                val event = dummyPairEvent(pair = otherPair)

                every { pairService.findUser("inviter") } returns inviter
                every { pairService.findConnectedPair(inviter) } returns pair
                every { pairEventRepository.findByIdOrNull(1L) } returns event

                Then("CustomException(RESOURCE_NOT_FOUND) 발생") {
                    val ex = shouldThrow<CustomException> { service.delete("inviter", 1L) }
                    ex.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
                }
            }

            When("이벤트 없음") {
                every { pairService.findUser("inviter") } returns inviter
                every { pairService.findConnectedPair(inviter) } returns pair
                every { pairEventRepository.findByIdOrNull(999L) } returns null

                Then("CustomException(RESOURCE_NOT_FOUND) 발생") {
                    val ex = shouldThrow<CustomException> { service.delete("inviter", 999L) }
                    ex.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
                }
            }
        }
    })
