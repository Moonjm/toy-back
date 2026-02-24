package com.example.backend.categories.entity

import com.example.backend.categories.Category
import com.example.backend.common.entity.withId

fun dummyCategory(
    emoji: String = "🍎",
    name: String = "테스트카테고리",
    isActive: Boolean = true,
    sortOrder: Int = 1,
    id: Long = 1L,
): Category =
    Category(
        emoji = emoji,
        name = name,
        isActive = isActive,
        sortOrder = sortOrder,
    ).withId(id)
