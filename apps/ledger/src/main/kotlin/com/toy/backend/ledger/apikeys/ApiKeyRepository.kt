package com.toy.backend.ledger.apikeys

import com.toy.backend.user.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ApiKeyRepository : JpaRepository<ApiKey, Long> {
    @Query("select k from ApiKey k join fetch k.user where k.keyHash = :keyHash")
    fun findWithUserByKeyHash(
        @Param("keyHash") keyHash: String,
    ): ApiKey?

    fun findAllByUser(user: User): List<ApiKey>
}
