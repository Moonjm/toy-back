package com.example.backend.auth.security

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty

class JwtServiceTest :
    BehaviorSpec({
        val secret = "this-is-a-test-secret-key-that-is-long-enough-for-hs256-algorithm"
        val expireMinutes = 30L
        val expireDays = 7L
        val jwtProperties =
            JwtProperties(
                secret = secret,
                accessTokenExpireMinutes = expireMinutes,
                refreshTokenExpireDays = expireDays,
            )
        val jwtService = JwtService(jwtProperties)

        Given("유효한 JwtProperties로 생성된 JwtService") {
            When("createAccessToken 호출") {
                val token = jwtService.createAccessToken("testuser", "USER")

                Then("JWT 문자열이 반환된다") {
                    token.shouldNotBeEmpty()
                }

                Then("parseClaims로 파싱 시 subject와 authority가 일치한다") {
                    val claims = jwtService.parseClaims(token)
                    claims.subject shouldBe "testuser"
                    claims["authority"] shouldBe "USER"
                }
            }

            When("accessTokenMaxAgeSeconds 호출") {
                val result = jwtService.accessTokenMaxAgeSeconds()

                Then("expireMinutes * 60 이어야 한다") {
                    result shouldBe expireMinutes * 60
                }
            }

            When("refreshTokenMaxAgeSeconds 호출") {
                val result = jwtService.refreshTokenMaxAgeSeconds()

                Then("expireDays * 86400 이어야 한다") {
                    result shouldBe expireDays * 24 * 60 * 60
                }
            }
        }

        Given("잘못된 토큰") {
            When("parseClaims 호출") {
                Then("예외가 발생한다") {
                    shouldThrow<Exception> {
                        jwtService.parseClaims("invalid.token.value")
                    }
                }
            }
        }
    })
