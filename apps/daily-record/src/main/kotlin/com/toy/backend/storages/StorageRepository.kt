package com.toy.backend.storages

import com.toy.backend.user.User
import org.springframework.data.jpa.repository.JpaRepository

interface StorageRepository : JpaRepository<Storage, Long> {
    fun findAllByUserOrderBySortOrderAsc(user: User): List<Storage>

    fun findAllByPairIdOrderBySortOrderAsc(pairId: Long): List<Storage>

    fun findTopByUserOrderBySortOrderDesc(user: User): Storage?

    fun findTopByPairIdOrderBySortOrderDesc(pairId: Long): Storage?
}
