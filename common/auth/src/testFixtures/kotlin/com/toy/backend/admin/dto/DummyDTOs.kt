package com.toy.backend.admin.dto

import com.toy.backend.admin.AdminUserUpdateRequest
import com.toy.backend.user.Authority

fun dummyAdminUserUpdateRequest(
    password: String = "newpass",
    name: String = "새이름",
    authority: Authority = Authority.ADMIN,
) = AdminUserUpdateRequest(
    password = password,
    name = name,
    authority = authority,
)
