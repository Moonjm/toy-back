package com.toy.backend.file

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}

/**
 * TTL 을 넘긴 TEMP 파일(첨부되지 않은 업로드, detach 잔재)을 수거한다.
 * S3 삭제(외부 I/O)를 트랜잭션 안에서 반복하지 않도록 [FileService] 와 분리했다 —
 * ① 대상 조회 → ② 트랜잭션 밖에서 파일별 S3 삭제 → ③ 성공한 건만 레코드 일괄 삭제.
 */
@Service
@ConditionalOnProperty(prefix = "s3", name = ["endpoint"])
class FileCleanupService(
    private val s3Client: S3Client,
    private val repository: FileRepository,
) {
    fun purgeExpiredTempFiles(cutoff: LocalDateTime): Int {
        val expired = repository.findAllByStatusAndUpdatedAtBefore(FileStatus.TEMP, cutoff)
        if (expired.isEmpty()) return 0

        // 삭제에 실패한 건은 레코드를 남겨 다음 주기에 재시도한다 (S3 삭제는 멱등이라 반복 호출이 안전하다).
        val purgedIds =
            expired.mapNotNull { file ->
                try {
                    s3Client.deleteObject(
                        DeleteObjectRequest
                            .builder()
                            .bucket(file.bucketName)
                            .key(file.storedName)
                            .build(),
                    )
                    file.requiredId
                } catch (e: Exception) {
                    logger.warn(e) { "만료 temp 파일 삭제 실패 — 다음 주기 재시도. id=${file.id}, key=${file.storedName}" }
                    null
                }
            }
        if (purgedIds.isEmpty()) return 0

        repository.deleteByIdInAndStatus(purgedIds, FileStatus.TEMP)
        return purgedIds.size
    }
}
