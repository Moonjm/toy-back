package com.example.backend.dailyrecords

import com.example.backend.categories.CategoryRepository
import com.example.backend.categories.entity.dummyCategory
import com.example.backend.common.constant.ErrorCode
import com.example.backend.common.exception.CustomException
import com.example.backend.common.utils.findAllNotNull
import com.example.backend.dailyrecords.dto.dummyDailyRecordRequest
import com.example.backend.dailyrecords.entity.dummyDailyRecord
import com.example.backend.user.UserRepository
import com.example.backend.user.entity.dummyUser
import com.linecorp.kotlinjdsl.dsl.jpql.Jpql
import com.linecorp.kotlinjdsl.querymodel.jpql.JpqlQueryable
import com.linecorp.kotlinjdsl.querymodel.jpql.select.SelectQuery
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import org.springframework.data.repository.findByIdOrNull

class DailyRecordServiceTest :
    BehaviorSpec({
        val repository = mockk<DailyRecordRepository>()
        val categoryRepository = mockk<CategoryRepository>()
        val userRepository = mockk<UserRepository>()
        val service = DailyRecordService(repository, categoryRepository, userRepository)

        val user = dummyUser()
        val category = dummyCategory()

        Given("목록 조회 시") {
            When("정상 요청") {
                val record = dummyDailyRecord(user = user, category = category)

                every { userRepository.findByUsername("testuser") } returns user
                mockkStatic("com.example.backend.common.utils.KotlinJdslExtensionsKt")
                every {
                    repository.findAllNotNull(any<Jpql.() -> JpqlQueryable<SelectQuery<DailyRecord>>>())
                } returns listOf(record)

                val result = service.list("testuser", null, null, null)

                Then("DailyRecordResponse 리스트 반환") {
                    result.size shouldBe 1
                    result[0].id shouldBe 1L
                }
            }
        }

        Given("생성 시") {
            When("정상 요청") {
                val request = dummyDailyRecordRequest()
                val savedRecord = dummyDailyRecord(user = user, category = category)

                every { userRepository.findByUsername("testuser") } returns user
                every { categoryRepository.findByIdOrNull(1L) } returns category
                every { repository.save(any()) } returns savedRecord

                val result = service.create("testuser", request)

                Then("저장 후 ID 반환") {
                    result shouldBe 1L
                }
            }

            When("카테고리 없음") {
                val request = dummyDailyRecordRequest(categoryId = 999L)

                every { categoryRepository.findByIdOrNull(999L) } returns null

                Then("CustomException(RESOURCE_NOT_FOUND) 발생") {
                    val ex = shouldThrow<CustomException> { service.create("testuser", request) }
                    ex.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
                }
            }
        }

        Given("수정 시") {
            When("정상 요청") {
                val record = dummyDailyRecord(user = user, category = category)
                val newCategory = dummyCategory(emoji = "🏊", name = "수영", id = 2L)
                val request = dummyDailyRecordRequest(categoryId = 2L, memo = "메모")

                every { userRepository.findByUsername("testuser") } returns user
                every { repository.findByIdAndUser(1L, user) } returns record
                every { categoryRepository.findByIdOrNull(2L) } returns newCategory

                service.update("testuser", 1L, request)

                Then("엔티티가 업데이트된다") {
                    record.category shouldBe newCategory
                    record.memo shouldBe "메모"
                }
            }

            When("기록 없음") {
                every { userRepository.findByUsername("testuser") } returns user
                every { repository.findByIdAndUser(999L, user) } returns null

                Then("CustomException(RESOURCE_NOT_FOUND) 발생") {
                    val ex =
                        shouldThrow<CustomException> {
                            service.update("testuser", 999L, dummyDailyRecordRequest())
                        }
                    ex.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
                }
            }
        }

        Given("삭제 시") {
            When("정상 요청") {
                val record = dummyDailyRecord(user = user, category = category)

                every { userRepository.findByUsername("testuser") } returns user
                every { repository.findByIdAndUser(1L, user) } returns record
                justRun { repository.delete(record) }

                service.delete("testuser", 1L)

                Then("delete 호출") {
                    verify { repository.delete(record) }
                }
            }

            When("기록 없음") {
                every { userRepository.findByUsername("testuser") } returns user
                every { repository.findByIdAndUser(999L, user) } returns null

                Then("CustomException(RESOURCE_NOT_FOUND) 발생") {
                    val ex = shouldThrow<CustomException> { service.delete("testuser", 999L) }
                    ex.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
                }
            }
        }
    })
