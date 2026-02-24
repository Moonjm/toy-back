package com.example.backend.common.constant

import org.springframework.http.HttpStatus

interface Code {
    fun getHttpStatus(): HttpStatus

    fun getMessage(): String

    fun getStatusName(): String
}
