package com.example.backend.user.entity

import com.example.backend.common.entity.withId
import com.example.backend.user.Authority
import com.example.backend.user.Gender
import com.example.backend.user.User
import java.time.LocalDate

fun dummyUser(
    username: String = "testuser",
    name: String = "테스트",
    passwordHash: String = "hashedpw",
    authority: Authority = Authority.USER,
    gender: Gender? = null,
    birthDate: LocalDate? = null,
    id: Long = 1L,
): User =
    User(
        username = username,
        name = name,
        passwordHash = passwordHash,
        authority = authority,
        gender = gender,
        birthDate = birthDate,
    ).withId(id)
