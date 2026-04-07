package com.toy.backend.storages.dto

import com.toy.backend.storages.ItemRequest
import com.toy.backend.storages.SectionCreateRequest
import com.toy.backend.storages.SectionMoveRequest
import com.toy.backend.storages.SectionUpdateRequest
import com.toy.backend.storages.StorageCreateRequest
import com.toy.backend.storages.StorageMoveRequest
import com.toy.backend.storages.StorageUpdateRequest

fun dummyStorageCreateRequest(
    name: String = "냉장고",
    storageType: String? = "fridge",
) = StorageCreateRequest(name = name, storageType = storageType)

fun dummyStorageUpdateRequest(
    name: String = "김치냉장고",
    storageType: String? = "kimchi",
) = StorageUpdateRequest(name = name, storageType = storageType)

fun dummySectionCreateRequest(name: String = "선반1") = SectionCreateRequest(name = name)

fun dummySectionUpdateRequest(name: String = "선반2") = SectionUpdateRequest(name = name)

fun dummyStorageMoveRequest(
    targetId: Long = 1L,
    beforeId: Long? = null,
) = StorageMoveRequest(targetId = targetId, beforeId = beforeId)

fun dummySectionMoveRequest(
    targetId: Long = 1L,
    beforeId: Long? = null,
) = SectionMoveRequest(targetId = targetId, beforeId = beforeId)

fun dummyItemRequest(
    name: String = "우유",
    quantity: Int = 1,
    category: String? = "dairy_milk",
    sectionId: Long = 1L,
) = ItemRequest(name = name, quantity = quantity, category = category, sectionId = sectionId)
