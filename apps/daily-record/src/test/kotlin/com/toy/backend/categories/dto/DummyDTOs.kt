package com.toy.backend.categories.dto

import com.toy.backend.categories.CategoryMoveRequest
import com.toy.backend.categories.CategoryRequest

fun dummyCategoryRequest(
    emoji: String = "🍎",
    name: String = "테스트카테고리",
    isActive: Boolean = true,
) = CategoryRequest(emoji = emoji, name = name, isActive = isActive)

fun dummyCategoryMoveRequest(
    targetId: Long = 1L,
    beforeId: Long? = null,
) = CategoryMoveRequest(targetId = targetId, beforeId = beforeId)
