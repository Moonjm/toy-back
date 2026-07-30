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
        registerCopyCleanup(
            tempBucket = entity.bucketName,
            tempStoredName = tempStoredName,
            copyBucket = properties.bucket,
            copyStoredName = newStoredName,
        )
        return entity.requiredId
    }

    /**
     * 커밋되면 temp 원본을, 롤백되면 방금 만든 사본을 지운다. **양쪽 다 필요하다.**
     *
     * - 커밋: `storedName` 이 영구 경로로 확정되므로 temp 원본은 쓸모가 없다.
     * - 롤백: DB 행은 temp 경로로 되돌아가지만 **S3 복사는 트랜잭션 밖이라 되돌아가지 않는다.**
     *   사본을 지우지 않으면 그것을 가리키는 레코드가 없어 정리 배치도 못 찾는 영구 고아가 된다
     *   (배치는 `TEMP` 행의 `storedName`, 즉 temp 경로만 지운다). 스토리지가 쌓이는 것보다
     *   **저장에 실패한 개인 사진이 무기한 남는 것**이 문제다.
     *
     * 끼니 확정처럼 한 트랜잭션에서 여러 장을 붙이는 경로는 중간 실패 시 앞서 복사한 사본이
     * 전부 여기 걸린다 — 장수만큼 고아가 생기던 자리다.
     */
    private fun registerCopyCleanup(
        tempBucket: String,
        tempStoredName: String,
        copyBucket: String,
        copyStoredName: String,
    ) {
        // 트랜잭션이 없으면 되돌아갈 일도 없다 — 복사는 이미 확정이므로 원본만 정리한다.
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return deleteQuietly(tempBucket, tempStoredName)
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                // afterCommit 이 아니라 afterCompletion 을 쓴다 — 롤백에서도 호출되어야 한다.
                override fun afterCompletion(status: Int) {
                    if (status == TransactionSynchronization.STATUS_COMMITTED) {
                        deleteQuietly(tempBucket, tempStoredName)
                    } else {
                        deleteQuietly(copyBucket, copyStoredName)
                    }
                }
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
