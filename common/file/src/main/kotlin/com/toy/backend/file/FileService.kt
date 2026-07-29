package com.toy.backend.file

import com.toy.backend.common.constant.ErrorCode
import com.toy.backend.common.exception.CustomException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.multipart.MultipartFile
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CopyObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.nio.file.Paths
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

        private fun sanitizeFileName(originalFilename: String?): String =
            originalFilename
                ?.takeIf { it.isNotBlank() }
                ?.let { Paths.get(it).fileName.toString() }
                ?: "unknown"

        private fun extensionOf(fileName: String): String = fileName.lastIndexOf('.').let { if (it > 0) fileName.substring(it) else "" }
    }

    @Transactional
    fun upload(file: MultipartFile): Long {
        val originalName = sanitizeFileName(file.originalFilename)
        // 저장 키에는 원본 파일명을 쓰지 않는다 — 경로 구분자·공백·한글이 키 구조와 URL 인코딩을 흔든다.
        val storedName = "$TEMP_PREFIX${UUID.randomUUID()}${extensionOf(originalName)}"
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

    /**
     * 저장된 객체의 바이트를 그대로 읽는다. 이미지 인식처럼 서버가 파일 내용을 직접 다뤄야 하는
     * 경우에만 쓴다 — 클라이언트에게 줄 때는 presigned URL이 맞다.
     */
    fun download(id: Long): FileContent {
        val entity =
            repository.findByIdOrNull(id)
                ?: throw CustomException(ErrorCode.RESOURCE_NOT_FOUND, id)
        val bytes =
            s3Client.getObjectAsBytes(
                GetObjectRequest
                    .builder()
                    .bucket(entity.bucketName)
                    .key(entity.storedName)
                    .build(),
            )
        return FileContent(bytes = bytes.asByteArray(), contentType = entity.contentType)
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

        // detach 로 되돌아온 파일은 경로가 이미 영구 경로라 재연결을 지원하지 않는다 —
        // 파일을 다시 업로드해 새 id 로 연결해야 한다. 남은 레코드·객체는 정리 배치가 TTL 후 수거한다.
        if (!entity.storedName.startsWith(TEMP_PREFIX)) {
            throw CustomException(ErrorCode.INVALID_REQUEST, "연결이 해제된 파일은 재사용할 수 없습니다. 파일을 다시 업로드해 주세요.")
        }

        val tempStoredName = entity.storedName
        val newStoredName = "$targetPrefix${tempStoredName.removePrefix(TEMP_PREFIX)}"

        s3Client.copyObject(
            CopyObjectRequest
                .builder()
                .sourceBucket(entity.bucketName)
                .sourceKey(tempStoredName)
                .destinationBucket(properties.bucket)
                .destinationKey(newStoredName)
                .build(),
        )

        entity.attach(newStoredName)
        deleteTempAfterCommit(entity.bucketName, tempStoredName)
        return entity.requiredId
    }

    // temp 원본은 커밋이 확정된 뒤에만 지운다 — 롤백되면 storedName 도 temp 경로로 되돌아가므로
    // 원본이 남아 있어야 레코드와 객체가 계속 맞물린다.
    private fun deleteTempAfterCommit(
        bucket: String,
        storedName: String,
    ) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return deleteQuietly(bucket, storedName)
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = deleteQuietly(bucket, storedName)
            },
        )
    }

    private fun deleteQuietly(
        bucket: String,
        storedName: String,
    ) {
        try {
            s3Client.deleteObject(
                DeleteObjectRequest
                    .builder()
                    .bucket(bucket)
                    .key(storedName)
                    .build(),
            )
        } catch (e: Exception) {
            logger.warn(e) { "temp 원본 삭제 실패 — 고아 객체로 남는다. key=$storedName" }
        }
    }

    /**
     * 도메인에서 연결이 끊긴 파일을 수거 대상(TEMP)으로 되돌린다. 물리 삭제는 정리 배치가 하므로
     * 도메인 트랜잭션이 롤백되면 파일도 함께 되살아난다. 존재하지 않는 id 는 무시한다.
     */
    @Transactional
    fun detachFile(id: Long) {
        val entity =
            repository.findByIdOrNull(id)
                ?: return logger.warn { "연결을 해제할 파일을 찾을 수 없습니다. id=$id" }
        entity.detach()
    }

    @Transactional
    fun detachFiles(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        repository.findAllById(ids).forEach { it.detach() }
    }
}

data class FileContent(
    val bytes: ByteArray,
    val contentType: String,
)
