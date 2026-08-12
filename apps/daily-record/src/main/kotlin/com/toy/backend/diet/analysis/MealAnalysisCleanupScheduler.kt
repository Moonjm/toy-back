package com.toy.backend.diet.analysis

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}

/**
 * 확인 화면에서 이탈해 확정되지 않은 분석 레코드를 지운다. 사진 파일은 `attachFile`이 호출되지
 * 않아 `TEMP`로 남고, `common-file`의 정리 배치(04:00)가 따로 수거한다. 그 뒤에 돌도록 04:10에 둔다.
 */
@Component
class MealAnalysisCleanupScheduler(
    private val service: MealAnalysisCleanupService,
) {
    // cutoff 는 createdAt(감사 필드)과 같은 시계를 써야 하므로 zone 인자 없는 now() 를 쓴다.
    @Scheduled(cron = "0 10 4 * * *")
    fun purgeExpiredAnalyses() {
        val cutoff = LocalDateTime.now().minus(ANALYSIS_TTL)
        try {
            val purged = service.purgeExpired(cutoff)
            if (purged > 0) logger.info { "만료 식단 분석 정리 완료: ${purged}건 (cutoff=$cutoff)" }
        } catch (e: Exception) {
            logger.error(e) { "만료 식단 분석 정리 실패 (cutoff=$cutoff)" }
        }
    }

    companion object {
        private val ANALYSIS_TTL: Duration = Duration.ofHours(24)
    }
}
