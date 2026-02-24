package com.example.backend.common.utils

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object TokenHasher {
    fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
