package com.example.backend.auth.dto

import com.example.backend.auth.LoginRequest
import com.example.backend.auth.RegisterRequest

fun dummyRegisterRequest(
    username: String = "newuser",
    name: String = "새유저",
    password: String = "pass123",
) = RegisterRequest(username = username, name = name, password = password)

fun dummyLoginRequest(
    username: String = "testuser",
    password: String = "correctpw",
) = LoginRequest(username = username, password = password)
