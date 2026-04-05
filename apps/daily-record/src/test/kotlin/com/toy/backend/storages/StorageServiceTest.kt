package com.toy.backend.storages

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.entity.withId
import com.toy.backend.common.exception.CustomException
import com.toy.backend.pair.PairConnection
import com.toy.backend.pair.PairService
import com.toy.backend.storages.dto.dummyItemRequest
import com.toy.backend.storages.dto.dummySectionCreateRequest
import com.toy.backend.storages.dto.dummySectionUpdateRequest
import com.toy.backend.storages.dto.dummyStorageCreateRequest
import com.toy.backend.storages.dto.dummyStorageUpdateRequest
import com.toy.backend.storages.entity.dummyItem
import com.toy.backend.storages.entity.dummySection
import com.toy.backend.storages.entity.dummyStorage
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

class StorageServiceTest :
    BehaviorSpec({
        val storageRepository = mockk<StorageRepository>()
        val sectionRepository = mockk<StorageSectionRepository>()
        val itemRepository = mockk<StorageItemRepository>()
        val userRepository = mockk<UserRepository>()
        val pairService = mockk<PairService>()
        val service = StorageService(storageRepository, sectionRepository, itemRepository, userRepository, pairService)

        val user = dummyUser()
        val username = user.username

        Given("보관함 목록 조회") {
            When("페어가 없는 개인 사용자") {
                val storage = dummyStorage(user = user, name = "냉장고")
                val section = dummySection(storage = storage, name = "윗칸")

                every { userRepository.findByUsername(username) } returns user
                every { pairService.findConnectedPair(user) } returns null
                every { storageRepository.findAllByUserOrderBySortOrderAsc(user) } returns listOf(storage)
                every { sectionRepository.findAllByStorageInOrderBySortOrderAsc(listOf(storage)) } returns listOf(section)
                every { itemRepository.findAllBySectionIn(listOf(section)) } returns emptyList()

                val result = service.listStorages(username)

                Then("개인 보관함 반환") {
                    result.size shouldBe 1
                    result[0].name shouldBe "냉장고"
                    result[0].sections.size shouldBe 1
                }
            }

            When("페어가 있는 사용자") {
                val partner = dummyUser(username = "partner", name = "파트너", id = 2L)
                val pair = PairConnection(inviter = user, inviteCode = "ABC123").withId(10L)
                pair.accept(partner)
                val storage = dummyStorage(pairId = 10L, name = "냉장고")

                every { userRepository.findByUsername(username) } returns user
                every { pairService.findConnectedPair(user) } returns pair
                every { storageRepository.findAllByPairIdOrderBySortOrderAsc(10L) } returns listOf(storage)
                every { sectionRepository.findAllByStorageInOrderBySortOrderAsc(listOf(storage)) } returns emptyList()

                val result = service.listStorages(username)

                Then("페어 보관함 반환") {
                    result.size shouldBe 1
                }
            }
        }

        Given("보관함 생성") {
            When("페어 없는 개인 사용자") {
                val request = dummyStorageCreateRequest(name = "냉장고", sections = listOf("윗칸"))

                every { userRepository.findByUsername(username) } returns user
                every { pairService.findConnectedPair(user) } returns null
                every { storageRepository.findTopByUserOrderBySortOrderDesc(user) } returns null
                every { storageRepository.save(any()) } answers { firstArg<Storage>().withId(1L) }
                every { sectionRepository.save(any()) } answers { firstArg<StorageSection>().withId(1L) }

                val id = service.createStorage(username, request)

                Then("userId 기반으로 저장") {
                    id shouldBe 1L
                    verify { storageRepository.save(match { it.user == user && it.pairId == null }) }
                }
            }

            When("페어가 있는 사용자") {
                val partner = dummyUser(username = "partner", name = "파트너", id = 2L)
                val pair = PairConnection(inviter = user, inviteCode = "ABC123").withId(10L)
                pair.accept(partner)
                val request = dummyStorageCreateRequest(name = "냉장고", sections = emptyList())

                every { userRepository.findByUsername(username) } returns user
                every { pairService.findConnectedPair(user) } returns pair
                every { storageRepository.findTopByPairIdOrderBySortOrderDesc(10L) } returns null
                every { storageRepository.save(any()) } answers { firstArg<Storage>().withId(2L) }

                val id = service.createStorage(username, request)

                Then("pairId 기반으로 저장") {
                    id shouldBe 2L
                    verify { storageRepository.save(match { it.user == null && it.pairId == 10L }) }
                }
            }
        }

        Given("보관함 수정") {
            When("접근 권한 없는 보관함") {
                val otherStorage = dummyStorage(pairId = 999L, name = "남의 냉장고", id = 99L)

                every { userRepository.findByUsername(username) } returns user
                every { pairService.findConnectedPair(user) } returns null
                every { storageRepository.findByIdOrNull(99L) } returns otherStorage

                Then("STORAGE_ACCESS_DENIED 발생") {
                    val ex =
                        shouldThrow<CustomException> {
                            service.updateStorage(username, 99L, dummyStorageUpdateRequest())
                        }
                    ex.errorCode shouldBe ErrorCode.STORAGE_ACCESS_DENIED
                }
            }

            When("정상 수정") {
                val storage = dummyStorage(user = user, name = "냉장고")

                every { userRepository.findByUsername(username) } returns user
                every { pairService.findConnectedPair(user) } returns null
                every { storageRepository.findByIdOrNull(1L) } returns storage

                service.updateStorage(username, 1L, dummyStorageUpdateRequest(name = "김치냉장고"))

                Then("이름 변경") {
                    storage.name shouldBe "김치냉장고"
                }
            }
        }

        Given("보관함 삭제") {
            When("정상 삭제") {
                val storage = dummyStorage(user = user, name = "냉장고")
                val section = dummySection(storage = storage)

                every { userRepository.findByUsername(username) } returns user
                every { pairService.findConnectedPair(user) } returns null
                every { storageRepository.findByIdOrNull(1L) } returns storage
                every { sectionRepository.findAllByStorageOrderBySortOrderAsc(storage) } returns listOf(section)
                justRun { itemRepository.deleteAllBySectionIn(listOf(section)) }
                justRun { sectionRepository.deleteAllByStorage(storage) }
                justRun { storageRepository.delete(storage) }

                service.deleteStorage(username, 1L)

                Then("하위 구역, 품목 모두 삭제") {
                    verify { itemRepository.deleteAllBySectionIn(listOf(section)) }
                    verify { sectionRepository.deleteAllByStorage(storage) }
                    verify { storageRepository.delete(storage) }
                }
            }
        }

        Given("구역 추가") {
            When("정상 추가") {
                val storage = dummyStorage(user = user)

                every { userRepository.findByUsername(username) } returns user
                every { pairService.findConnectedPair(user) } returns null
                every { storageRepository.findByIdOrNull(1L) } returns storage
                every { sectionRepository.findTopByStorageOrderBySortOrderDesc(storage) } returns null
                every { sectionRepository.save(any()) } answers { firstArg<StorageSection>().withId(5L) }

                val id = service.createSection(username, 1L, dummySectionCreateRequest())

                Then("구역 ID 반환") {
                    id shouldBe 5L
                }
            }
        }

        Given("구역 수정") {
            When("정상 수정") {
                val storage = dummyStorage(user = user)
                val section = dummySection(storage = storage, name = "윗칸")

                every { userRepository.findByUsername(username) } returns user
                every { pairService.findConnectedPair(user) } returns null
                every { storageRepository.findByIdOrNull(1L) } returns storage
                every { sectionRepository.findByIdAndStorage(1L, storage) } returns section

                service.updateSection(username, 1L, 1L, dummySectionUpdateRequest(name = "아랫칸"))

                Then("이름 변경") {
                    section.name shouldBe "아랫칸"
                }
            }

            When("존재하지 않는 구역") {
                val storage = dummyStorage(user = user)

                every { userRepository.findByUsername(username) } returns user
                every { pairService.findConnectedPair(user) } returns null
                every { storageRepository.findByIdOrNull(1L) } returns storage
                every { sectionRepository.findByIdAndStorage(999L, storage) } returns null

                Then("RESOURCE_NOT_FOUND 발생") {
                    val ex =
                        shouldThrow<CustomException> {
                            service.updateSection(username, 1L, 999L, dummySectionUpdateRequest())
                        }
                    ex.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
                }
            }
        }

        Given("품목 추가") {
            When("정상 추가") {
                val storage = dummyStorage(user = user)
                val section = dummySection(storage = storage)

                every { userRepository.findByUsername(username) } returns user
                every { pairService.findConnectedPair(user) } returns null
                every { storageRepository.findByIdOrNull(1L) } returns storage
                every { sectionRepository.findByIdAndStorage(1L, storage) } returns section
                every { itemRepository.save(any()) } answers { firstArg<StorageItem>().withId(10L) }

                val id = service.createItem(username, 1L, dummyItemRequest(sectionId = 1L))

                Then("품목 ID 반환") {
                    id shouldBe 10L
                }
            }
        }

        Given("품목 삭제") {
            When("정상 삭제") {
                val storage = dummyStorage(user = user)
                val section = dummySection(storage = storage)
                val item = dummyItem(section = section, createdByUser = user, id = 10L)

                every { userRepository.findByUsername(username) } returns user
                every { pairService.findConnectedPair(user) } returns null
                every { storageRepository.findByIdOrNull(1L) } returns storage
                every { sectionRepository.findAllByStorageOrderBySortOrderAsc(storage) } returns listOf(section)
                every { itemRepository.findByIdAndSectionIn(10L, listOf(section)) } returns item
                justRun { itemRepository.delete(item) }

                service.deleteItem(username, 1L, 10L)

                Then("delete 호출") {
                    verify { itemRepository.delete(item) }
                }
            }
        }
    })
