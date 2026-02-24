package com.example.backend.admin.dto

import com.example.backend.admin.AdminUserUpdateRequest
import com.example.backend.user.Authority

fun dummyAdminUserUpdateRequest(
    password: String = "newpass",
    name: String = "새이름",
    authority: Authority = Authority.ADMIN,
) = AdminUserUpdateRequest(
    password = password,
    name = name,
    authority = authority,
)
