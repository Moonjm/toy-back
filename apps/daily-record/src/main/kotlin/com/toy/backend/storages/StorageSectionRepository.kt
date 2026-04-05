package com.toy.backend.storages

import org.springframework.data.jpa.repository.JpaRepository

interface StorageSectionRepository : JpaRepository<StorageSection, Long> {
    fun findAllByStorageOrderBySortOrderAsc(storage: Storage): List<StorageSection>

    fun findAllByStorageInOrderBySortOrderAsc(storages: List<Storage>): List<StorageSection>

    fun findByIdAndStorage(
        id: Long,
        storage: Storage,
    ): StorageSection?

    fun findTopByStorageOrderBySortOrderDesc(storage: Storage): StorageSection?

    fun deleteAllByStorage(storage: Storage)
}
