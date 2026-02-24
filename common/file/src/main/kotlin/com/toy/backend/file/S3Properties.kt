package com.toy.backend.file

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties

@ConditionalOnProperty(prefix = "s3", name = ["endpoint"])
@ConfigurationProperties(prefix = "s3")
data class S3Properties(
    val endpoint: String,
    val publicEndpoint: String? = null,
    val region: String = "ap-northeast-2",
    val accessKey: String,
    val secretKey: String,
    val bucket: String,
)
