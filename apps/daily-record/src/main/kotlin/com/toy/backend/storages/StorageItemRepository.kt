package com.toy.backend.storages

import org.springframework.data.jpa.repository.JpaRepository

interface StorageItemRepository : JpaRepository<StorageItem, Long> {
    fun findAllBySectionIn(sections: List<StorageSection>): List<StorageItem>

    fun findByIdAndSectionIn(
        id: Long,
        sections: List<StorageSection>,
    ): StorageItem?

    fun deleteAllBySectionIn(sections: List<StorageSection>)

    fun deleteAllBySection(section: StorageSection)
}
