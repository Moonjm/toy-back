package com.toy.backend.file

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.net.URI

@Configuration
@ConditionalOnProperty(prefix = "s3", name = ["endpoint"])
class S3Config(
    private val properties: S3Properties,
) {
    @Bean
    fun s3Client(): S3Client =
        S3Client
            .builder()
            .endpointOverride(URI.create(properties.endpoint))
            .region(Region.of(properties.region))
            .credentialsProvider(credentialsProvider())
            .forcePathStyle(true)
            .build()

    @Bean
    fun s3Presigner(): S3Presigner =
        S3Presigner
            .builder()
            .endpointOverride(URI.create(properties.publicEndpoint ?: properties.endpoint))
            .region(Region.of(properties.region))
            .credentialsProvider(credentialsProvider())
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build()

    private fun credentialsProvider(): StaticCredentialsProvider =
        StaticCredentialsProvider.create(
            AwsBasicCredentials.create(properties.accessKey, properties.secretKey),
        )
}
