package com.example.backend.auth

import com.example.backend.auth.dto.dummyLoginRequest
import com.example.backend.auth.dto.dummyRegisterRequest
import com.example.backend.auth.entity.dummyRefreshToken
import com.example.backend.auth.security.JwtProperties
import com.example.backend.auth.security.JwtService
import com.example.backend.auth.security.RefreshToken
import com.example.backend.auth.security.RefreshTokenRepository
import com.example.backend.common.constant.ErrorCode
import com.example.backend.common.exception.CustomException
import com.example.backend.common.utils.TokenHasher
import com.example.backend.user.UserRepository
import com.example.backend.user.entity.dummyUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.crypto.password.PasswordEncoder

class AuthServiceTest :
    BehaviorSpec({
        val userRepository = mockk<UserRepository>()
        val refreshTokenRepository = mockk<RefreshTokenRepository>()
        val passwordEncoder = mockk<PasswordEncoder>()
        val jwtService = mockk<JwtService>()
        val jwtProperties =
            JwtProperties(
                secret = "test-secret",
                accessTokenExpireMinutes = 120,
                refreshTokenExpireDays = 14,
            )
        val cookieService = mockk<AuthCookieService>()

        val authService =
            AuthService(
                userRepository = userRepository,
                refreshTokenRepository = refreshTokenRepository,
                passwordEncoder = passwordEncoder,
                jwtService = jwtService,
                jwtProperties = jwtProperties,
                cookieService = cookieService,
            )

        Given("회원가입 시") {
            When("정상 요청") {
                val request = dummyRegisterRequest()
                val savedUser = dummyUser(username = "newuser", name = "새유저", passwordHash = "encoded")

                every { userRepository.existsByUsername("newuser") } returns false
                every { passwordEncoder.encode("pass123") } returns "encoded"
                every { userRepository.save(any()) } returns savedUser

                val result = authService.register(request)

                Then("user 저장 후 ID 반환") {
                    result shouldBe 1L
                    verify { userRepository.save(any()) }
                }
            }

            When("중복 username") {
                val request = dummyRegisterRequest(username = "existing")
                every { userRepository.existsByUsername("existing") } returns true

                Then("CustomException(DUPLICATE_RESOURCE) 발생") {
                    val ex = shouldThrow<CustomException> { authService.register(request) }
                    ex.errorCode shouldBe ErrorCode.DUPLICATE_RESOURCE
                }
            }
        }

        Given("로그인 시") {
            val response = mockk<HttpServletResponse>(relaxed = true)
            val user = dummyUser()

            When("정상 요청") {
                val request = dummyLoginRequest()

                every { userRepository.findByUsername("testuser") } returns user
                every { passwordEncoder.matches("correctpw", "hashedpw") } returns true
                justRun { refreshTokenRepository.deleteAllByUserId(1L) }
                every { jwtService.createAccessToken("testuser", "USER") } returns "access-jwt"
                every { refreshTokenRepository.save(any<RefreshToken>()) } answers { firstArg() }
                every { jwtService.accessTokenMaxAgeSeconds() } returns 7200L
                every { jwtService.refreshTokenMaxAgeSeconds() } returns 1209600L
                justRun { cookieService.applyAuthCookies(any(), any(), any(), any(), any()) }

                authService.login(request, response)

                Then("쿠키 적용, 기존 리프레시 토큰 삭제 후 새로 저장") {
                    verify { refreshTokenRepository.deleteAllByUserId(1L) }
                    verify { refreshTokenRepository.save(any<RefreshToken>()) }
                    verify {
                        cookieService.applyAuthCookies(
                            response = response,
                            accessToken = "access-jwt",
                            refreshToken = any(),
                            accessMaxAgeSeconds = 7200L,
                            refreshMaxAgeSeconds = 1209600L,
                        )
                    }
                }
            }

            When("유저 없음") {
                val request = dummyLoginRequest(username = "unknown", password = "pw")
                every { userRepository.findByUsername("unknown") } returns null

                Then("CustomException(RESOURCE_NOT_FOUND) 발생") {
                    val ex = shouldThrow<CustomException> { authService.login(request, response) }
                    ex.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
                }
            }

            When("비밀번호 불일치") {
                val request = dummyLoginRequest(password = "wrongpw")
                every { userRepository.findByUsername("testuser") } returns user
                every { passwordEncoder.matches("wrongpw", "hashedpw") } returns false

                Then("CustomException(INVALID_REQUEST) 발생") {
                    val ex = shouldThrow<CustomException> { authService.login(request, response) }
                    ex.errorCode shouldBe ErrorCode.INVALID_REQUEST
                }
            }
        }

        Given("토큰 갱신 시") {
            val response = mockk<HttpServletResponse>(relaxed = true)
            val user = dummyUser()

            When("유효한 리프레시 토큰") {
                val rawToken = "valid-refresh-token"
                val hashed = TokenHasher.sha256(rawToken)
                val refreshToken = dummyRefreshToken(user = user, tokenHash = hashed)

                every {
                    refreshTokenRepository.findByTokenHashAndRevokedAtIsNullAndExpiresAtAfter(hashed, any())
                } returns refreshToken
                justRun { refreshTokenRepository.deleteAllByUserId(1L) }
                every { jwtService.createAccessToken("testuser", "USER") } returns "new-access-jwt"
                every { refreshTokenRepository.save(any<RefreshToken>()) } answers { firstArg() }
                every { jwtService.accessTokenMaxAgeSeconds() } returns 7200L
                every { jwtService.refreshTokenMaxAgeSeconds() } returns 1209600L
                justRun { cookieService.applyAuthCookies(any(), any(), any(), any(), any()) }

                authService.refresh(rawToken, response)

                Then("새 토큰 발급 및 쿠키 적용") {
                    verify { refreshTokenRepository.deleteAllByUserId(1L) }
                    verify { cookieService.applyAuthCookies(response, "new-access-jwt", any(), 7200L, 1209600L) }
                }
            }

            When("유효하지 않은 리프레시 토큰") {
                val rawToken = "invalid-token"
                val hashed = TokenHasher.sha256(rawToken)

                every {
                    refreshTokenRepository.findByTokenHashAndRevokedAtIsNullAndExpiresAtAfter(hashed, any())
                } returns null

                Then("CustomException(INVALID_REQUEST) 발생") {
                    val ex = shouldThrow<CustomException> { authService.refresh(rawToken, response) }
                    ex.errorCode shouldBe ErrorCode.INVALID_REQUEST
                }
            }
        }

        Given("로그아웃 시") {
            val response = mockk<HttpServletResponse>(relaxed = true)
            val user = dummyUser()

            When("유효한 리프레시 토큰") {
                val rawToken = "valid-refresh-token"
                val hashed = TokenHasher.sha256(rawToken)
                val refreshToken = dummyRefreshToken(user = user, tokenHash = hashed)

                every {
                    refreshTokenRepository.findByTokenHashAndRevokedAtIsNullAndExpiresAtAfter(hashed, any())
                } returns refreshToken
                justRun { refreshTokenRepository.deleteAllByUserId(1L) }
                justRun { cookieService.clearAuthCookies(response) }

                authService.logout(rawToken, response)

                Then("토큰 삭제 + 쿠키 클리어") {
                    verify { refreshTokenRepository.deleteAllByUserId(1L) }
                    verify { cookieService.clearAuthCookies(response) }
                }
            }

            When("null 리프레시 토큰") {
                justRun { cookieService.clearAuthCookies(response) }

                authService.logout(null, response)

                Then("쿠키만 클리어") {
                    verify { cookieService.clearAuthCookies(response) }
                }
            }
        }
    })
