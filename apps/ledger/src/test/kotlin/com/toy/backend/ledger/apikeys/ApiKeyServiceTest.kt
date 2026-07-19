package com.toy.backend.ledger.apikeys

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.entity.withId
import com.toy.backend.common.exception.CustomException
import com.toy.backend.common.utils.TokenHasher
import com.toy.backend.user.UserRepository
import com.toy.backend.user.entity.dummyUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.springframework.data.repository.findByIdOrNull

class ApiKeyServiceTest :
    BehaviorSpec({
        val repository = mockk<ApiKeyRepository>()
        val userRepository = mockk<UserRepository>()
        val service = ApiKeyService(repository, userRepository)

        val user = dummyUser()

        beforeTest {
            every { userRepository.findByUsername("testuser") } returns user
        }

        Given("API 키 발급") {
            When("정상 요청") {
                every { repository.save(any()) } answers { (firstArg() as ApiKey).withId(3L) }

                val result = service.issue("testuser", ApiKeyCreateRequest(name = "아이폰 단축어"))

                Then("원본 키가 응답에 포함되고 해시만 저장된다") {
                    result.id shouldBe 3L
                    result.key shouldNotBe null
                    result.key.length shouldBe 43 // base64url(32바이트) = 43자
                    verify {
                        repository.save(
                            match { it.keyHash == TokenHasher.sha256(result.key) && it.name == "아이폰 단축어" },
                        )
                    }
                }
            }
        }

        Given("API 키 폐기") {
            When("타인 소유 키면") {
                val other = dummyUser(username = "other", id = 2L)
                val key = ApiKey(user = other, keyHash = "h", name = "k").withId(9L)
                every { repository.findByIdOrNull(9L) } returns key

                Then("RESOURCE_NOT_FOUND 예외") {
                    val e = shouldThrow<CustomException> { service.delete("testuser", 9L) }
                    e.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
                }
            }

            When("본인 소유 키면") {
                val key = ApiKey(user = user, keyHash = "h", name = "k").withId(4L)
                every { repository.findByIdOrNull(4L) } returns key
                justRun { repository.delete(key) }

                service.delete("testuser", 4L)

                Then("삭제 호출") {
                    verify { repository.delete(key) }
                }
            }
        }
    })
