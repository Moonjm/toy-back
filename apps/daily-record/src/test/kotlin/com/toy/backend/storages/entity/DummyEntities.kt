package com.toy.backend.storages.entity

import com.toy.backend.common.entity.withId
import com.toy.backend.storages.Storage
import com.toy.backend.storages.StorageItem
import com.toy.backend.storages.StorageSection
import com.toy.backend.user.User

fun dummyStorage(
    user: User? = null,
    pairId: Long? = null,
    name: String = "냉장고",
    storageType: String? = "fridge",
    sortOrder: Int = 0,
    id: Long = 1L,
): Storage = Storage(user = user, pairId = pairId, name = name, storageType = storageType, sortOrder = sortOrder).withId(id)

fun dummySection(
    storage: Storage,
    name: String = "윗칸",
    sortOrder: Int = 0,
    id: Long = 1L,
): StorageSection = StorageSection(storage = storage, name = name, sortOrder = sortOrder).withId(id)

fun dummyItem(
    section: StorageSection,
    name: String = "우유",
    quantity: Int = 1,
    category: String? = "dairy_milk",
    createdByUser: User,
    id: Long = 1L,
): StorageItem =
    StorageItem(
        section = section,
        name = name,
        quantity = quantity,
        category = category,
        createdByUser = createdByUser,
    ).withId(id)
