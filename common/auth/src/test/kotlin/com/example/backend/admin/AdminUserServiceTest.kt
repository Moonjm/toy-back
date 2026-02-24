package com.example.backend.admin

import com.example.backend.admin.dto.dummyAdminUserUpdateRequest
import com.example.backend.auth.security.RefreshTokenRepository
import com.example.backend.common.constant.ErrorCode
import com.example.backend.common.exception.CustomException
import com.example.backend.user.Authority
import com.example.backend.user.UserRepository
import com.example.backend.user.entity.dummyUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.springframework.data.repository.findByIdOrNull
import org.springframework.security.crypto.password.PasswordEncoder

class AdminUserServiceTest :
    BehaviorSpec({
        val userRepository = mockk<UserRepository>()
        val refreshTokenRepository = mockk<RefreshTokenRepository>()
        val passwordEncoder = mockk<PasswordEncoder>()
        val adminUserService = AdminUserService(userRepository, refreshTokenRepository, passwordEncoder)

        Given("유저 목록 조회 시") {
            When("list 호출") {
                val admin = dummyUser(username = "admin", name = "관리자", id = 1L)
                val user2 = dummyUser(username = "user2", name = "유저2", id = 2L)
                val user3 = dummyUser(username = "user3", name = "유저3", id = 3L)

                every { userRepository.findByUsername("admin") } returns admin
                every { userRepository.findAll() } returns listOf(admin, user3, user2)

                val result = adminUserService.list("admin")

                Then("본인 제외, id 순 정렬된 UserResponse 리스트") {
                    result.size shouldBe 2
                    result[0].id shouldBe 2L
                    result[0].username shouldBe "user2"
                    result[1].id shouldBe 3L
                    result[1].username shouldBe "user3"
                }
            }
        }

        Given("유저 단건 조회 시") {
            When("존재하는 유저") {
                val user = dummyUser()
                every { userRepository.findByIdOrNull(1L) } returns user

                val result = adminUserService.get(1L)

                Then("UserResponse 반환") {
                    result.id shouldBe 1L
                    result.username shouldBe "testuser"
                    result.name shouldBe "테스트"
                    result.authority shouldBe Authority.USER
                }
            }

            When("없는 유저") {
                every { userRepository.findByIdOrNull(999L) } returns null

                Then("CustomException(RESOURCE_NOT_FOUND) 발생") {
                    val ex = shouldThrow<CustomException> { adminUserService.get(999L) }
                    ex.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
                }
            }
        }

        Given("유저 수정 시") {
            When("정상 요청") {
                val user = dummyUser(name = "기존이름", passwordHash = "oldhash")

                every { userRepository.findByIdOrNull(1L) } returns user
                every { passwordEncoder.encode("newpass") } returns "newhash"

                adminUserService.update(1L, dummyAdminUserUpdateRequest())

                Then("updateCredentials + updateProfile 호출 확인") {
                    user.passwordHash shouldBe "newhash"
                    user.authority shouldBe Authority.ADMIN
                    user.name shouldBe "새이름"
                }
            }
        }

        Given("유저 삭제 시") {
            When("정상 요청") {
                val user = dummyUser(name = "삭제대상")

                every { userRepository.findByIdOrNull(1L) } returns user
                justRun { refreshTokenRepository.deleteAllByUserId(1L) }
                justRun { userRepository.delete(user) }

                adminUserService.delete(1L)

                Then("리프레시 토큰 삭제 + 유저 삭제") {
                    verify { refreshTokenRepository.deleteAllByUserId(1L) }
                    verify { userRepository.delete(user) }
                }
            }
        }
    })
