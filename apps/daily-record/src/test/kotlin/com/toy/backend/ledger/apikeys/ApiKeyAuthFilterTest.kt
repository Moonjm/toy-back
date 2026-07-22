package com.toy.backend.ledger.apikeys

import com.toy.backend.common.entity.withId
import com.toy.backend.common.utils.TokenHasher
import com.toy.backend.user.entity.dummyUser
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder

class ApiKeyAuthFilterTest :
    BehaviorSpec({
        val repository = mockk<ApiKeyRepository>()
        val filter = ApiKeyAuthFilter(repository)

        beforeContainer { SecurityContextHolder.clearContext() }
        afterContainer { SecurityContextHolder.clearContext() }

        // 실제 배포는 컨텍스트 경로(/api) 아래에서 서빙된다 — requestURI가 아닌 servletPath로 매칭해야 한다.
        fun inboundRequest(apiKey: String?): MockHttpServletRequest =
            MockHttpServletRequest("POST", "/inbound").apply {
                contextPath = "/api"
                requestURI = "/api/inbound"
                servletPath = "/inbound"
                apiKey?.let { addHeader("X-API-Key", it) }
            }

        Given("유효한 API 키로 inbound 요청") {
            When("필터 통과") {
                every { repository.findWithUserByKeyHash(TokenHasher.sha256("valid-key")) } returns
                    ApiKey(user = dummyUser(), keyHash = TokenHasher.sha256("valid-key"), name = "단축어").withId(1L)

                filter.doFilter(inboundRequest("valid-key"), MockHttpServletResponse(), MockFilterChain())

                Then("사용자명으로 인증 컨텍스트 설정") {
                    SecurityContextHolder.getContext().authentication?.name shouldBe "testuser"
                }
            }
        }

        Given("잘못된 API 키") {
            When("필터 통과") {
                every { repository.findWithUserByKeyHash(any()) } returns null

                filter.doFilter(inboundRequest("wrong-key"), MockHttpServletResponse(), MockFilterChain())

                Then("인증 컨텍스트 미설정 (이후 401)") {
                    SecurityContextHolder.getContext().authentication.shouldBeNull()
                }
            }
        }

        Given("inbound 외 경로에 API 키") {
            When("필터 통과") {
                val request =
                    MockHttpServletRequest("GET", "/entries").apply {
                        contextPath = "/api"
                        requestURI = "/api/entries"
                        servletPath = "/entries"
                        addHeader("X-API-Key", "valid-key")
                    }

                filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

                Then("API 키 인증을 적용하지 않는다") {
                    SecurityContextHolder.getContext().authentication.shouldBeNull()
                }
            }
        }
    })
