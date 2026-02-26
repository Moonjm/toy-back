package com.toy.backend.user

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

interface UserRepository : JpaRepository<User, Long> {
    fun findByUsername(username: String): User?

    fun existsByUsername(username: String): Boolean

    fun findByAuthorityOrderByIdAsc(authority: Authority): List<User>

    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(
        "UPDATE User u SET u.failedLoginAttempts = u.failedLoginAttempts + 1," +
            " u.locked = CASE WHEN u.failedLoginAttempts + 1 >= 5 THEN true ELSE u.locked END" +
            " WHERE u.id = :id",
    )
    fun incrementFailedAttemptsById(id: Long)
}
