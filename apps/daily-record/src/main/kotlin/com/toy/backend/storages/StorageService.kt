package com.toy.backend.storages

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import com.toy.backend.pair.PairService
import com.toy.backend.user.User
import com.toy.backend.user.UserRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class StorageService(
    private val storageRepository: StorageRepository,
    private val sectionRepository: StorageSectionRepository,
    private val itemRepository: StorageItemRepository,
    private val userRepository: UserRepository,
    private val pairService: PairService,
) {
    // ── 보관함 ──

    fun listStorages(username: String): List<StorageResponse> {
        val user = findUser(username)
        val storages = findUserStorages(user)
        val allSections = if (storages.isNotEmpty()) sectionRepository.findAllByStorageInOrderBySortOrderAsc(storages) else emptyList()
        val allItems = if (allSections.isNotEmpty()) itemRepository.findAllBySectionIn(allSections) else emptyList()

        val itemsBySection = allItems.groupBy { it.section.requiredId }
        val sectionsByStorage = allSections.groupBy { it.storage.requiredId }

        return storages.map { storage ->
            val sections = sectionsByStorage[storage.requiredId] ?: emptyList()
            storage.toResponse(
                sections.map { section ->
                    val items = itemsBySection[section.requiredId] ?: emptyList()
                    section.toResponse(items.map { it.toResponse() })
                },
            )
        }
    }

    @Transactional
    fun createStorage(username: String, request: StorageCreateRequest): Long {
        val user = findUser(username)
        val pair = pairService.findConnectedPair(user)
        val nextSortOrder = nextStorageSortOrder(user, pair?.requiredId)

        val storage = Storage(
            user = if (pair == null) user else null,
            pairId = pair?.requiredId,
            name = request.name,
            sortOrder = nextSortOrder,
        )
        storageRepository.save(storage)

        request.sections.forEachIndexed { index, sectionName ->
            sectionRepository.save(StorageSection(storage = storage, name = sectionName, sortOrder = index))
        }

        return storage.requiredId
    }

    @Transactional
    fun updateStorage(username: String, id: Long, request: StorageUpdateRequest) {
        val storage = findOwnedStorage(username, id)
        storage.updateName(request.name)
    }

    @Transactional
    fun deleteStorage(username: String, id: Long) {
        val storage = findOwnedStorage(username, id)
        val sections = sectionRepository.findAllByStorageOrderBySortOrderAsc(storage)
        if (sections.isNotEmpty()) {
            itemRepository.deleteAllBySectionIn(sections)
            sectionRepository.deleteAllByStorage(storage)
        }
        storageRepository.delete(storage)
    }

    // ── 구역 ──

    @Transactional
    fun createSection(username: String, storageId: Long, request: SectionCreateRequest): Long {
        val storage = findOwnedStorage(username, storageId)
        val nextSortOrder = (sectionRepository.findTopByStorageOrderBySortOrderDesc(storage)?.sortOrder ?: -1) + 1
        val section = StorageSection(storage = storage, name = request.name, sortOrder = nextSortOrder)
        return sectionRepository.save(section).requiredId
    }

    @Transactional
    fun updateSection(username: String, storageId: Long, sectionId: Long, request: SectionUpdateRequest) {
        val storage = findOwnedStorage(username, storageId)
        val section = sectionRepository.findByIdAndStorage(sectionId, storage)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, sectionId)
        section.updateName(request.name)
    }

    @Transactional
    fun deleteSection(username: String, storageId: Long, sectionId: Long) {
        val storage = findOwnedStorage(username, storageId)
        val section = sectionRepository.findByIdAndStorage(sectionId, storage)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, sectionId)
        itemRepository.deleteAllBySection(section)
        sectionRepository.delete(section)
    }

    // ── 품목 ──

    fun listItems(username: String, storageId: Long): List<SectionResponse> {
        val storage = findOwnedStorage(username, storageId)
        val sections = sectionRepository.findAllByStorageOrderBySortOrderAsc(storage)
        val items = if (sections.isNotEmpty()) itemRepository.findAllBySectionIn(sections) else emptyList()
        val itemsBySection = items.groupBy { it.section.requiredId }

        return sections.map { section ->
            section.toResponse((itemsBySection[section.requiredId] ?: emptyList()).map { it.toResponse() })
        }
    }

    @Transactional
    fun createItem(username: String, storageId: Long, request: ItemRequest): Long {
        val storage = findOwnedStorage(username, storageId)
        val section = sectionRepository.findByIdAndStorage(request.sectionId, storage)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, request.sectionId)
        val user = findUser(username)
        val item = StorageItem(
            section = section,
            name = request.name,
            quantity = request.quantity,
            expiryDate = request.expiryDate,
            createdByUser = user,
        )
        return itemRepository.save(item).requiredId
    }

    @Transactional
    fun updateItem(username: String, storageId: Long, itemId: Long, request: ItemRequest) {
        val storage = findOwnedStorage(username, storageId)
        val sections = sectionRepository.findAllByStorageOrderBySortOrderAsc(storage)
        val item = itemRepository.findByIdAndSectionIn(itemId, sections)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, itemId)
        val targetSection = sectionRepository.findByIdAndStorage(request.sectionId, storage)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, request.sectionId)
        item.updateDetails(name = request.name, quantity = request.quantity, expiryDate = request.expiryDate, section = targetSection)
    }

    @Transactional
    fun deleteItem(username: String, storageId: Long, itemId: Long) {
        val storage = findOwnedStorage(username, storageId)
        val sections = sectionRepository.findAllByStorageOrderBySortOrderAsc(storage)
        val item = itemRepository.findByIdAndSectionIn(itemId, sections)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, itemId)
        itemRepository.delete(item)
    }

    // ── 내부 ──

    private fun findUser(username: String): User =
        userRepository.findByUsername(username)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, username)

    private fun findUserStorages(user: User): List<Storage> {
        val pair = pairService.findConnectedPair(user)
        return if (pair != null) {
            storageRepository.findAllByPairIdOrderBySortOrderAsc(pair.requiredId)
        } else {
            storageRepository.findAllByUserOrderBySortOrderAsc(user)
        }
    }

    private fun findOwnedStorage(username: String, storageId: Long): Storage {
        val user = findUser(username)
        val storage = storageRepository.findByIdOrNull(storageId)
            ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, storageId)

        val pair = pairService.findConnectedPair(user)
        val hasAccess = if (pair != null) {
            storage.pairId == pair.requiredId
        } else {
            storage.user?.requiredId == user.requiredId && storage.pairId == null
        }

        if (!hasAccess) {
            throw CustomException(ErrorCode.STORAGE_ACCESS_DENIED)
        }
        return storage
    }

    private fun nextStorageSortOrder(user: User, pairId: Long?): Int {
        val last = if (pairId != null) {
            storageRepository.findTopByPairIdOrderBySortOrderDesc(pairId)
        } else {
            storageRepository.findTopByUserOrderBySortOrderDesc(user)
        }
        return (last?.sortOrder ?: -1) + 1
    }
}
