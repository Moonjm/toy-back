package com.toy.backend.user

import org.springframework.data.jpa.repository.JpaRepository

interface UserRepository : JpaRepository<User, Long> {
    fun findByUsername(username: String): User?

    fun existsByUsername(username: String): Boolean

    fun findByAuthorityOrderByIdAsc(authority: Authority): List<User>
}
