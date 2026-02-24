package com.toy.backend.categories

import com.toy.backend.categories.dto.dummyCategoryMoveRequest
import com.toy.backend.categories.dto.dummyCategoryRequest
import com.toy.backend.categories.entity.dummyCategory
import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.entity.withId
import com.toy.backend.common.exception.CustomException
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
        val service = CategoryService(repository)

        Given("목록 조회 시") {
            val cat1 = dummyCategory(emoji = "🍎", name = "사과", sortOrder = 1, id = 1L)
            val cat2 = dummyCategory(emoji = "🍌", name = "바나나", sortOrder = 2, id = 2L)

            When("active가 null") {
                every { repository.findAllByOrderBySortOrderAscIdAsc() } returns listOf(cat1, cat2)

                val result = service.list(null)

                Then("전체 목록 반환") {
                    result.size shouldBe 2
                    result[0].name shouldBe "사과"
                }
            }

            When("active가 true") {
                every { repository.findAllByIsActiveOrderBySortOrderAscIdAsc(true) } returns listOf(cat1)

                val result = service.list(true)

                Then("활성 목록만 반환") {
                    result.size shouldBe 1
                }
            }
        }

        Given("단건 조회 시") {
            When("존재하는 카테고리") {
                val cat = dummyCategory()
                every { repository.findByIdOrNull(1L) } returns cat

                val result = service.get(1L)

                Then("CategoryResponse 반환") {
                    result.id shouldBe 1L
                    result.emoji shouldBe "🍎"
                }
            }

            When("없는 카테고리") {
                every { repository.findByIdOrNull(999L) } returns null

                Then("CustomException(RESOURCE_NOT_FOUND) 발생") {
                    val ex = shouldThrow<CustomException> { service.get(999L) }
                    ex.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
                }
            }
        }

        Given("생성 시") {
            When("정상 요청") {
                val request = dummyCategoryRequest()
                val lastCategory = dummyCategory(sortOrder = 3)

                every { repository.findTopByOrderBySortOrderDescIdDesc() } returns lastCategory
                every { repository.save(any()) } answers { firstArg<Category>().withId() }

                val result = service.create(request)

                Then("nextSortOrder로 저장") {
                    result.sortOrder shouldBe 4
                }
            }

            When("첫 카테고리 생성") {
                val request = dummyCategoryRequest()

                every { repository.findTopByOrderBySortOrderDescIdDesc() } returns null
                every { repository.save(any()) } answers { firstArg<Category>().withId() }

                val result = service.create(request)

                Then("sortOrder 1로 저장") {
                    result.sortOrder shouldBe 1
                }
            }

            When("emoji가 빈 문자열") {
                val request = dummyCategoryRequest(emoji = " ")

                Then("CustomException(INVALID_REQUEST) 발생") {
                    val ex = shouldThrow<CustomException> { service.create(request) }
                    ex.errorCode shouldBe ErrorCode.INVALID_REQUEST
                }
            }
        }

        Given("수정 시") {
            When("정상 요청") {
                val cat = dummyCategory(emoji = "🍎", name = "기존")
                val request = dummyCategoryRequest(emoji = "🍌", name = "변경", isActive = false)
                every { repository.findByIdOrNull(1L) } returns cat

                service.update(1L, request)

                Then("엔티티가 업데이트된다") {
                    cat.emoji shouldBe "🍌"
                    cat.name shouldBe "변경"
                    cat.isActive shouldBe false
                }
            }

            When("없는 카테고리") {
                every { repository.findByIdOrNull(999L) } returns null

                Then("CustomException(RESOURCE_NOT_FOUND) 발생") {
                    val ex =
                        shouldThrow<CustomException> {
                            service.update(999L, dummyCategoryRequest())
                        }
                    ex.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
                }
            }
        }

        Given("삭제 시") {
            When("정상 요청") {
                val cat = dummyCategory()
                every { repository.findByIdOrNull(1L) } returns cat
                justRun { repository.delete(cat) }

                service.delete(1L)

                Then("delete 호출") {
                    verify { repository.delete(cat) }
                }
            }

            When("없는 카테고리") {
                every { repository.findByIdOrNull(999L) } returns null

                Then("CustomException(RESOURCE_NOT_FOUND) 발생") {
                    val ex = shouldThrow<CustomException> { service.delete(999L) }
                    ex.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
                }
            }
        }

        Given("순서 변경 시") {
            When("정상 이동 (beforeId 지정)") {
                val cat1 = dummyCategory(sortOrder = 1, id = 1L)
                val cat2 = dummyCategory(sortOrder = 2, id = 2L)
                val cat3 = dummyCategory(sortOrder = 3, id = 3L)
                every { repository.findAllByOrderBySortOrderAscIdAsc() } returns listOf(cat1, cat2, cat3)
                every { repository.saveAll(any<List<Category>>()) } answers { firstArg() }

                val request = dummyCategoryMoveRequest(targetId = 3L, beforeId = 1L)
                service.move(request)

                Then("target이 before 앞으로 이동하고 sortOrder 재정렬") {
                    cat3.sortOrder shouldBe 1
                    cat1.sortOrder shouldBe 2
                    cat2.sortOrder shouldBe 3
                }
            }

            When("targetId == beforeId") {
                val request = dummyCategoryMoveRequest(targetId = 1L, beforeId = 1L)

                Then("CustomException(INVALID_REQUEST) 발생") {
                    val ex = shouldThrow<CustomException> { service.move(request) }
                    ex.errorCode shouldBe ErrorCode.INVALID_REQUEST
                }
            }
        }
    })
