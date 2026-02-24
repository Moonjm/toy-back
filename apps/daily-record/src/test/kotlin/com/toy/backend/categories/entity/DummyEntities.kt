package com.toy.backend.categories.entity

import com.toy.backend.categories.Category
import com.toy.backend.common.entity.withId

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
