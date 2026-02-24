package com.example.backend.user

import com.example.backend.common.constant.ErrorCode
import com.example.backend.common.exception.CustomException
import com.example.backend.user.dto.dummyUserUpdateRequest
import com.example.backend.user.entity.dummyUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.LocalDate

class UserServiceTest :
    BehaviorSpec({
        val userRepository = mockk<UserRepository>()
        val passwordEncoder = mockk<PasswordEncoder>()
        val userService = UserService(userRepository, passwordEncoder)

        Given("me 조회 시") {
            When("존재하는 유저") {
                val user =
                    dummyUser(
                        gender = Gender.MALE,
                        birthDate = LocalDate.of(1990, 1, 15),
                    )
                every { userRepository.findByUsername("testuser") } returns user

                val result = userService.me("testuser")

                Then("UserResponse 반환") {
                    result.id shouldBe 1L
                    result.username shouldBe "testuser"
                    result.name shouldBe "테스트"
                    result.authority shouldBe Authority.USER
                    result.gender shouldBe "MALE"
                    result.birthDate shouldBe "1990-01-15"
                }
            }

            When("없는 유저") {
                every { userRepository.findByUsername("unknown") } returns null

                Then("CustomException(RESOURCE_NOT_FOUND) 발생") {
                    val ex = shouldThrow<CustomException> { userService.me("unknown") }
                    ex.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
                }
            }
        }

        Given("프로필 수정 시") {
            When("이름/성별/생년월일 변경") {
                val user = dummyUser(name = "기존이름")
                every { userRepository.findByUsername("testuser") } returns user

                val request =
                    dummyUserUpdateRequest(
                        name = "새이름",
                        gender = "FEMALE",
                        birthDate = LocalDate.of(1995, 6, 20),
                    )
                userService.updateMe("testuser", request)

                Then("프로필이 업데이트된다") {
                    user.name shouldBe "새이름"
                    user.gender shouldBe Gender.FEMALE
                    user.birthDate shouldBe LocalDate.of(1995, 6, 20)
                }
            }

            When("비밀번호 변경 (currentPassword 일치)") {
                val user = dummyUser(username = "pwuser", passwordHash = "oldhash", id = 2L)
                every { userRepository.findByUsername("pwuser") } returns user
                every { passwordEncoder.matches("oldpw", "oldhash") } returns true
                every { passwordEncoder.encode("newpw") } returns "newhash"

                val request = dummyUserUpdateRequest(currentPassword = "oldpw", password = "newpw")
                userService.updateMe("pwuser", request)

                Then("비밀번호가 업데이트된다") {
                    user.passwordHash shouldBe "newhash"
                }
            }

            When("비밀번호 변경 (currentPassword 불일치)") {
                val user = dummyUser(username = "pwuser2", passwordHash = "oldhash", id = 3L)
                every { userRepository.findByUsername("pwuser2") } returns user
                every { passwordEncoder.matches("wrongpw", "oldhash") } returns false

                val request = dummyUserUpdateRequest(currentPassword = "wrongpw", password = "newpw")

                Then("CustomException(INVALID_REQUEST) 발생") {
                    val ex = shouldThrow<CustomException> { userService.updateMe("pwuser2", request) }
                    ex.errorCode shouldBe ErrorCode.INVALID_REQUEST
                }
            }

            When("비밀번호 변경 (currentPassword 누락)") {
                val user = dummyUser(username = "pwuser3", passwordHash = "oldhash", id = 4L)
                every { userRepository.findByUsername("pwuser3") } returns user

                val request = dummyUserUpdateRequest(password = "newpw")

                Then("CustomException(INVALID_REQUEST) 발생") {
                    val ex = shouldThrow<CustomException> { userService.updateMe("pwuser3", request) }
                    ex.errorCode shouldBe ErrorCode.INVALID_REQUEST
                }
            }
        }
    })
