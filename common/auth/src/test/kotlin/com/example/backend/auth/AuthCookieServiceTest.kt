package com.example.backend.auth

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders

class AuthCookieServiceTest :
    BehaviorSpec({
        val cookieService = AuthCookieService()

        Given("토큰과 maxAge가 주어졌을 때") {
            val token = "test-token-value"
            val maxAge = 3600L

            When("accessTokenCookie 호출") {
                val cookie = cookieService.accessTokenCookie(token, maxAge)

                Then("name=access_token, httpOnly, sameSite=Lax, path=/ 확인") {
                    cookie.name shouldBe "access_token"
                    cookie.value shouldBe token
                    cookie.maxAge.seconds shouldBe maxAge
                    cookie.isHttpOnly shouldBe true
                    cookie.sameSite shouldBe "Lax"
                    cookie.path shouldBe "/"
                    cookie.isSecure shouldBe false
                }
            }

            When("refreshTokenCookie 호출") {
                val cookie = cookieService.refreshTokenCookie(token, maxAge)

                Then("name=refresh_token 속성 확인") {
                    cookie.name shouldBe "refresh_token"
                    cookie.value shouldBe token
                    cookie.maxAge.seconds shouldBe maxAge
                    cookie.isHttpOnly shouldBe true
                    cookie.sameSite shouldBe "Lax"
                    cookie.path shouldBe "/"
                }
            }
        }

        Given("쿠키 삭제 시") {
            When("clearAccessTokenCookie 호출") {
                val cookie = cookieService.clearAccessTokenCookie()

                Then("maxAge=0, value 빈 문자열 확인") {
                    cookie.name shouldBe "access_token"
                    cookie.value shouldBe ""
                    cookie.maxAge.seconds shouldBe 0
                }
            }

            When("clearRefreshTokenCookie 호출") {
                val cookie = cookieService.clearRefreshTokenCookie()

                Then("maxAge=0, value 빈 문자열 확인") {
                    cookie.name shouldBe "refresh_token"
                    cookie.value shouldBe ""
                    cookie.maxAge.seconds shouldBe 0
                }
            }
        }

        Given("HttpServletResponse가 주어졌을 때") {
            When("applyAuthCookies 호출") {
                val response = mockk<HttpServletResponse>(relaxed = true)
                cookieService.applyAuthCookies(response, "access", "refresh", 3600, 86400)

                Then("Set-Cookie 헤더 2개 추가 확인") {
                    verify(exactly = 2) { response.addHeader(HttpHeaders.SET_COOKIE, any()) }
                }
            }

            When("clearAuthCookies 호출") {
                val response = mockk<HttpServletResponse>(relaxed = true)
                cookieService.clearAuthCookies(response)

                Then("빈 쿠키 2개 추가 확인") {
                    verify(exactly = 2) { response.addHeader(HttpHeaders.SET_COOKIE, any()) }
                }
            }
        }
    })
