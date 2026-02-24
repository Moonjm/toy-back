package com.example.backend.file

import com.example.backend.common.constant.ErrorCode
import com.example.backend.common.exception.CustomException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CopyObjectRequest
import software.amazon.awssdk.services.s3.model.Delete
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.time.Duration
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Service
@Transactional(readOnly = true)
@ConditionalOnProperty(prefix = "s3", name = ["endpoint"])
class FileService(
    private val s3Client: S3Client,
    private val s3Presigner: S3Presigner,
    private val repository: FileRepository,
    private val properties: S3Properties,
) {
    companion object {
        private const val TEMP_PREFIX = "temp/"
        private val PRESIGNED_URL_DURATION: Duration = Duration.ofMinutes(10)
    }

    @Transactional
    fun upload(file: MultipartFile): Long {
        val originalName = file.originalFilename ?: "unknown"
        val storedName = "$TEMP_PREFIX${UUID.randomUUID()}_$originalName"
        val contentType = file.contentType ?: "application/octet-stream"

        s3Client.putObject(
            PutObjectRequest
                .builder()
                .bucket(properties.bucket)
                .key(storedName)
                .contentType(contentType)
                .contentLength(file.size)
                .build(),
            RequestBody.fromInputStream(file.inputStream, file.size),
        )

        val entity =
            FileEntity(
                originalName = originalName,
                storedName = storedName,
                contentType = contentType,
                fileSize = file.size,
                bucketName = properties.bucket,
            )
        return repository.save(entity).requiredId
    }

    fun getPresignedUrl(id: Long): String {
        val entity =
            repository.findByIdOrNull(id)
                ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        return generatePresignedUrl(entity)
    }

    fun getPresignedUrls(ids: Collection<Long>): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        return repository.findAllById(ids).associate { it.requiredId to generatePresignedUrl(it) }
    }

    private fun generatePresignedUrl(entity: FileEntity): String {
        val presignRequest =
            GetObjectPresignRequest
                .builder()
                .signatureDuration(PRESIGNED_URL_DURATION)
                .getObjectRequest(
                    GetObjectRequest
                        .builder()
                        .bucket(entity.bucketName)
                        .key(entity.storedName)
                        .build(),
                ).build()
        return s3Presigner.presignGetObject(presignRequest).url().toString()
    }

    @Transactional
    fun attachFile(
        id: Long,
        targetPrefix: String,
    ): Long {
        val entity =
            repository.findByIdOrNull(id)
                ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)

        if (entity.status != FileStatus.TEMP) {
            throw CustomException(ErrorCode.INVALID_REQUEST, "이미 사용 중인 파일입니다.")
        }

        val newStoredName = "$targetPrefix${entity.storedName.removePrefix(TEMP_PREFIX)}"

        s3Client.copyObject(
            CopyObjectRequest
                .builder()
                .sourceBucket(entity.bucketName)
                .sourceKey(entity.storedName)
                .destinationBucket(properties.bucket)
                .destinationKey(newStoredName)
                .build(),
        )

        s3Client.deleteObject(
            DeleteObjectRequest
                .builder()
                .bucket(entity.bucketName)
                .key(entity.storedName)
                .build(),
        )

        entity.attach(newStoredName)
        return entity.requiredId
    }

    @Transactional
    fun delete(id: Long) {
        val entity =
            repository.findByIdOrNull(id)
                ?: return logger.warn { "삭제할 파일을 찾을 수 없습니다. id=$id" }
        s3Client.deleteObject(
            DeleteObjectRequest
                .builder()
                .bucket(entity.bucketName)
                .key(entity.storedName)
                .build(),
        )
        repository.delete(entity)
    }

    @Transactional
    fun deleteAll(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val entities = repository.findAllById(ids)
        if (entities.isEmpty()) return

        val objects =
            entities.map {
                ObjectIdentifier.builder().key(it.storedName).build()
            }
        s3Client.deleteObjects(
            DeleteObjectsRequest
                .builder()
                .bucket(properties.bucket)
                .delete(
                    Delete
                        .builder()
                        .objects(objects)
                        .quiet(true)
                        .build(),
                ).build(),
        )
        repository.deleteAll(entities)
    }
}
