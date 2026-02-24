package com.example.backend.categories

import org.springframework.data.jpa.repository.JpaRepository

interface CategoryRepository : JpaRepository<Category, Long> {
    fun findAllByIsActiveOrderBySortOrderAscIdAsc(isActive: Boolean): List<Category>

    fun findAllByOrderBySortOrderAscIdAsc(): List<Category>

    fun findTopByOrderBySortOrderDescIdDesc(): Category?
}
