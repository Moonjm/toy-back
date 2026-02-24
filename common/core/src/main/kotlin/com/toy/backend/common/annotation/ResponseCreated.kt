package com.toy.backend.common.annotation

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ResponseCreated(
    val path: String,
)
