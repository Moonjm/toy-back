package com.toy.backend.user.entity

import com.toy.backend.common.entity.withId
import com.toy.backend.user.Authority
import com.toy.backend.user.Gender
import com.toy.backend.user.User
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
