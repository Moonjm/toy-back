package com.toy.backend.file

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty(prefix = "s3", name = ["endpoint"])
class FileCleanupScheduler(
    private val fileCleanupService: FileCleanupService,
) {
    // cutoff 는 updatedAt(감사 필드)과 같은 시계를 써야 하므로 zone 인자 없는 now() 를 쓴다.
    @Scheduled(cron = "0 0 4 * * *")
    fun purgeExpiredTempFiles() {
        val cutoff = LocalDateTime.now().minus(TEMP_TTL)
        try {
            val purged = fileCleanupService.purgeExpiredTempFiles(cutoff)
            if (purged > 0) logger.info { "만료 temp 파일 정리 완료: ${purged}건 (cutoff=$cutoff)" }
        } catch (e: Exception) {
            logger.error(e) { "만료 temp 파일 정리 실패 (cutoff=$cutoff)" }
        }
    }

    companion object {
        private val TEMP_TTL: Duration = Duration.ofHours(24)
    }
}
