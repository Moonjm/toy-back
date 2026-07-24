package com.toy.backend.user.dto

import com.toy.backend.user.UserUpdateRequest
import java.time.LocalDate

fun dummyUserUpdateRequest(
    name: String? = null,
    gender: String? = null,
    birthDate: LocalDate? = null,
    membershipBarcode: String? = null,
    currentPassword: String? = null,
    password: String? = null,
) = UserUpdateRequest(
    name = name,
    gender = gender,
    birthDate = birthDate,
    membershipBarcode = membershipBarcode,
    currentPassword = currentPassword,
    password = password,
)
