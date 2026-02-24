package com.example.backend.dailyrecords

import com.example.backend.common.constant.ErrorCode
import com.example.backend.common.exception.CustomException
import com.example.backend.dailyrecords.dto.dummyDailyOvereatRequest
import com.example.backend.dailyrecords.entity.dummyDailyOvereat
import com.example.backend.pair.PairService
import com.example.backend.pair.PairStatus
import com.example.backend.pair.entity.dummyPairConnection
import com.example.backend.user.UserRepository
import com.example.backend.user.entity.dummyUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDate

class DailyOvereatServiceTest :
    BehaviorSpec({
        val repository = mockk<DailyOvereatRepository>()
        val userRepository = mockk<UserRepository>()
        val pairService = mockk<PairService>()
        val service = DailyOvereatService(repository, userRepository, pairService)

        val user = dummyUser()
        val date = LocalDate.of(2026, 2, 1)

        Given("페어 미연결 시 목록 조회") {
            When("정상 요청") {
                val from = LocalDate.of(2026, 2, 1)
                val to = LocalDate.of(2026, 2, 28)
                val overeat = dummyDailyOvereat(user = user)

                every { userRepository.findByUsername("testuser") } returns user
                every { pairService.findConnectedPair(user) } returns null
                every { repository.findAllByUserAndDateBetween(user, from, to) } returns listOf(overeat)

                val result = service.list("testuser", from, to)

                Then("본인 기준 DailyOvereatResponse 리스트 반환") {
                    result.size shouldBe 1
                    result[0].overeatLevel shouldBe OvereatLevel.MILD
                }
            }
        }

        Given("페어 연결 시 목록 조회") {
            val inviter = dummyUser(username = "inviter", name = "초대자", id = 1L)
            val partner = dummyUser(username = "partner", name = "파트너", id = 2L)
            val pair =
                dummyPairConnection(
                    inviter = inviter,
                    partner = partner,
                    status = PairStatus.CONNECTED,
                )

            When("파트너가 조회하면 inviter 기준으로 반환") {
                val from = LocalDate.of(2026, 2, 1)
                val to = LocalDate.of(2026, 2, 28)
                val overeat = dummyDailyOvereat(user = inviter)

                every { userRepository.findByUsername("partner") } returns partner
                every { pairService.findConnectedPair(partner) } returns pair
                every { repository.findAllByUserAndDateBetween(inviter, from, to) } returns listOf(overeat)

                val result = service.list("partner", from, to)

                Then("inviter 기준 데이터 반환") {
                    result.size shouldBe 1
                    result[0].overeatLevel shouldBe OvereatLevel.MILD
                }
            }
        }

        Given("페어 미연결 시 upsert") {
            When("기존 기록 없음, MILD") {
                val request = dummyDailyOvereatRequest()

                every { userRepository.findByUsername("testuser") } returns user
                every { pairService.findConnectedPair(user) } returns null
                every { repository.findByDateAndUser(date, user) } returns null
                every { repository.save(any()) } answers { firstArg() }

                service.upsert("testuser", request)

                Then("새 기록 저장") {
                    verify { repository.save(any()) }
                }
            }

            When("기존 기록 없음, NONE") {
                val request = dummyDailyOvereatRequest(overeatLevel = OvereatLevel.NONE)

                every { userRepository.findByUsername("testuser") } returns user
                every { pairService.findConnectedPair(user) } returns null
                every { repository.findByDateAndUser(date, user) } returns null

                service.upsert("testuser", request)

                Then("아무 동작 안 함") {
                    verify(exactly = 0) { repository.save(any()) }
                    verify(exactly = 0) { repository.delete(any()) }
                }
            }

            When("기존 기록 있음, 레벨 변경") {
                val existing = dummyDailyOvereat(user = user, overeatLevel = OvereatLevel.MILD)
                val request = dummyDailyOvereatRequest(overeatLevel = OvereatLevel.SEVERE)

                every { userRepository.findByUsername("testuser") } returns user
                every { pairService.findConnectedPair(user) } returns null
                every { repository.findByDateAndUser(date, user) } returns existing

                service.upsert("testuser", request)

                Then("레벨 업데이트") {
                    existing.overeatLevel shouldBe OvereatLevel.SEVERE
                }
            }

            When("기존 기록 있음, NONE으로 변경") {
                val existing = dummyDailyOvereat(user = user)
                val request = dummyDailyOvereatRequest(overeatLevel = OvereatLevel.NONE)

                every { userRepository.findByUsername("testuser") } returns user
                every { pairService.findConnectedPair(user) } returns null
                every { repository.findByDateAndUser(date, user) } returns existing
                justRun { repository.delete(existing) }

                service.upsert("testuser", request)

                Then("기존 기록 삭제") {
                    verify { repository.delete(existing) }
                }
            }
        }

        Given("페어 연결 시 upsert") {
            val inviter = dummyUser(username = "inviter", name = "초대자", id = 1L)
            val partner = dummyUser(username = "partner", name = "파트너", id = 2L)
            val pair =
                dummyPairConnection(
                    inviter = inviter,
                    partner = partner,
                    status = PairStatus.CONNECTED,
                )

            When("파트너가 upsert하면 inviter 기준으로 저장") {
                val request = dummyDailyOvereatRequest()

                every { userRepository.findByUsername("partner") } returns partner
                every { pairService.findConnectedPair(partner) } returns pair
                every { repository.findByDateAndUser(date, inviter) } returns null
                every { repository.save(any()) } answers { firstArg() }

                service.upsert("partner", request)

                Then("inviter user로 저장") {
                    verify {
                        repository.save(
                            match {
                                it.user.username == "inviter" && it.overeatLevel == OvereatLevel.MILD
                            },
                        )
                    }
                }
            }

            When("inviter가 upsert하면 본인 기준으로 저장") {
                val existing = dummyDailyOvereat(user = inviter, overeatLevel = OvereatLevel.MILD)
                val request = dummyDailyOvereatRequest(overeatLevel = OvereatLevel.SEVERE)

                every { userRepository.findByUsername("inviter") } returns inviter
                every { pairService.findConnectedPair(inviter) } returns pair
                every { repository.findByDateAndUser(date, inviter) } returns existing

                service.upsert("inviter", request)

                Then("레벨 업데이트") {
                    existing.overeatLevel shouldBe OvereatLevel.SEVERE
                }
            }
        }

        Given("유저 없음") {
            When("list 호출") {
                every { userRepository.findByUsername("unknown") } returns null

                Then("CustomException(RESOURCE_NOT_FOUND) 발생") {
                    val ex =
                        shouldThrow<CustomException> {
                            service.list("unknown", LocalDate.now(), LocalDate.now())
                        }
                    ex.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
                }
            }
        }
    })
