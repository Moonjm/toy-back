package com.example.backend.categories

import com.example.backend.common.entity.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "categories")
class Category(
    @Column(nullable = false, length = 16)
    var emoji: String,
    @Column(nullable = false, length = 50)
    var name: String,
    @Column(nullable = false)
    var isActive: Boolean = true,
    @Column(nullable = false)
    var sortOrder: Int = 0,
) : BaseEntity() {
    fun updateDetails(
        emoji: String,
        name: String,
        isActive: Boolean,
    ) {
        this.emoji = emoji
        this.name = name
        this.isActive = isActive
    }

    fun updateSortOrder(sortOrder: Int) {
        this.sortOrder = sortOrder
    }
}
