package com.toy.backend.ledger.categories

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.entity.withId
import com.toy.backend.common.exception.CustomException
import com.toy.backend.ledger.dummyCategory
import com.toy.backend.user.UserRepository
import com.toy.backend.user.entity.dummyUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.springframework.data.repository.findByIdOrNull

class CategoryServiceTest :
    BehaviorSpec({
        val repository = mockk<CategoryRepository>()
        val userRepository = mockk<UserRepository>()
        val service = CategoryService(repository, userRepository)

        val user = dummyUser()

        beforeTest {
            every { userRepository.findByUsername("testuser") } returns user
        }

        Given("분류 목록 조회") {
            When("조회") {
                every { repository.findAllByUserOrderByNameAsc(user) } returns listOf(dummyCategory(user = user, name = "식비"))

                val result = service.list("testuser")

                Then("이름순 응답 반환") {
                    result.size shouldBe 1
                    result[0].name shouldBe "식비"
                }
            }
        }

        Given("분류 생성") {
            When("새 이름이면") {
                every { repository.findByUserAndName(user, "교통비") } returns null
                every { repository.save(any()) } answers { (firstArg() as Category).withId(5L) }

                val id = service.create("testuser", CategoryRequest(name = "교통비"))

                Then("저장된 ID 반환") {
                    id shouldBe 5L
                    verify { repository.save(match { it.name == "교통비" }) }
                }
            }

            When("같은 이름이 이미 있으면") {
                every { repository.findByUserAndName(user, "식비") } returns dummyCategory(user = user)

                Then("DUPLICATE_RESOURCE 예외") {
                    val e = shouldThrow<CustomException> { service.create("testuser", CategoryRequest(name = "식비")) }
                    e.errorCode shouldBe ErrorCode.DUPLICATE_RESOURCE
                }
            }
        }

        Given("분류 수정") {
            When("본인 소유 분류의 이름을 바꾸면") {
                val category = dummyCategory(user = user, name = "식비", id = 2L)
                every { repository.findByIdOrNull(2L) } returns category
                every { repository.findByUserAndName(user, "외식비") } returns null

                service.update("testuser", 2L, CategoryRequest(name = "외식비"))

                Then("이름이 갱신된다") { category.name shouldBe "외식비" }
            }

            When("다른 분류가 쓰는 이름으로 바꾸면") {
                val category = dummyCategory(user = user, name = "식비", id = 3L)
                every { repository.findByIdOrNull(3L) } returns category
                every { repository.findByUserAndName(user, "교통비") } returns dummyCategory(user = user, name = "교통비", id = 4L)

                Then("DUPLICATE_RESOURCE 예외") {
                    val e = shouldThrow<CustomException> { service.update("testuser", 3L, CategoryRequest(name = "교통비")) }
                    e.errorCode shouldBe ErrorCode.DUPLICATE_RESOURCE
                }
            }

            When("타인 소유 분류면") {
                val other = dummyUser(username = "other", id = 2L)
                every { repository.findByIdOrNull(9L) } returns dummyCategory(user = other, id = 9L)

                Then("RESOURCE_NOT_FOUND 예외") {
                    val e = shouldThrow<CustomException> { service.update("testuser", 9L, CategoryRequest(name = "아무거나")) }
                    e.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
                }
            }
        }

        Given("분류 삭제") {
            When("본인 소유 분류면") {
                val category = dummyCategory(user = user, id = 7L)
                every { repository.findByIdOrNull(7L) } returns category
                justRun { repository.delete(category) }

                service.delete("testuser", 7L)

                Then("삭제 호출") { verify { repository.delete(category) } }
            }
        }
    })
