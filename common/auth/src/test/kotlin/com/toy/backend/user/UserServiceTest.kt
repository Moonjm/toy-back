package com.toy.backend.user

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import com.toy.backend.user.dto.dummyUserUpdateRequest
import com.toy.backend.user.entity.dummyUser
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

            When("회원카드 바코드 번호 등록") {
                val user = dummyUser(username = "barcodeuser", id = 5L)
                every { userRepository.findByUsername("barcodeuser") } returns user

                val request = dummyUserUpdateRequest(membershipBarcode = " 220290031 ")
                userService.updateMe("barcodeuser", request)

                Then("공백이 제거되어 저장된다") {
                    user.membershipBarcode shouldBe "220290031"
                }
            }

            When("회원카드 바코드 미전송(null)") {
                val user = dummyUser(username = "barcodeuser2", id = 6L)
                user.membershipBarcode = "220290031"
                every { userRepository.findByUsername("barcodeuser2") } returns user

                userService.updateMe("barcodeuser2", dummyUserUpdateRequest(name = "이름만"))

                Then("기존 바코드가 유지된다") {
                    user.membershipBarcode shouldBe "220290031"
                }
            }

            When("회원카드 바코드 빈 문자열 전송") {
                val user = dummyUser(username = "barcodeuser3", id = 7L)
                user.membershipBarcode = "220290031"
                every { userRepository.findByUsername("barcodeuser3") } returns user

                userService.updateMe("barcodeuser3", dummyUserUpdateRequest(membershipBarcode = "  "))

                Then("바코드가 삭제된다") {
                    user.membershipBarcode shouldBe null
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
