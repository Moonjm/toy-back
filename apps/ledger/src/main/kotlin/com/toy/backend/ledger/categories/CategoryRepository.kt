package com.toy.backend.ledger.categories

import com.toy.backend.user.User
import org.springframework.data.jpa.repository.JpaRepository

interface CategoryRepository : JpaRepository<Category, Long> {
    fun findAllByUserOrderByNameAsc(user: User): List<Category>

    fun findByUserAndName(
        user: User,
        name: String,
    ): Category?
}
