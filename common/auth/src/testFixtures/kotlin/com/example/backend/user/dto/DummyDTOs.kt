package com.example.backend.user.dto

import com.example.backend.user.UserUpdateRequest
import java.time.LocalDate

fun dummyUserUpdateRequest(
    name: String? = null,
    gender: String? = null,
    birthDate: LocalDate? = null,
    currentPassword: String? = null,
    password: String? = null,
) = UserUpdateRequest(
    name = name,
    gender = gender,
    birthDate = birthDate,
    currentPassword = currentPassword,
    password = password,
)
