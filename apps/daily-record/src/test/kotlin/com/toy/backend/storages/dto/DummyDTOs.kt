package com.toy.backend.storages.dto

import com.toy.backend.storages.ItemRequest
import com.toy.backend.storages.SectionCreateRequest
import com.toy.backend.storages.SectionUpdateRequest
import com.toy.backend.storages.StorageCreateRequest
import com.toy.backend.storages.StorageUpdateRequest

fun dummyStorageCreateRequest(
    name: String = "냉장고",
    sections: List<String> = listOf("윗칸", "아랫칸"),
) = StorageCreateRequest(name = name, sections = sections)

fun dummyStorageUpdateRequest(
    name: String = "김치냉장고",
) = StorageUpdateRequest(name = name)

fun dummySectionCreateRequest(
    name: String = "선반1",
) = SectionCreateRequest(name = name)

fun dummySectionUpdateRequest(
    name: String = "선반2",
) = SectionUpdateRequest(name = name)

fun dummyItemRequest(
    name: String = "우유",
    quantity: Int = 1,
    sectionId: Long = 1L,
) = ItemRequest(name = name, quantity = quantity, sectionId = sectionId)
