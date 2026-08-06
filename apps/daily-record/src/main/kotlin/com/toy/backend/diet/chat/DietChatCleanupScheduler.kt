package com.toy.backend.diet.chat

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}

/**
 * 오래된 대화를 지운다. 04:00(임시 파일)·04:10(분석) 뒤에 붙인다.
 *
 * **분석 TTL이 24시간인데 대화를 7일로 두는 이유** — 분석은 확정하면 지워지는 중간 산물이지만
 * 대화는 사용자가 직접 쓴 것이고, 다음 날 다시 열어 볼 만하다.
 */
@Component
class DietChatCleanupScheduler(
    private val service: DietChatCleanupService,
) {
    // cutoff 는 createdAt(감사 필드)과 같은 시계를 써야 하므로 zone 인자 없는 now() 를 쓴다.
    @Scheduled(cron = "0 20 4 * * *")
    fun purgeExpiredChats() {
        val cutoff = LocalDateTime.now().minus(CHAT_TTL)
        try {
            val purged = service.purgeExpired(cutoff)
            if (purged > 0) logger.info { "만료 하루 채팅 정리 완료: ${purged}건 (cutoff=$cutoff)" }
        } catch (e: Exception) {
            logger.error(e) { "만료 하루 채팅 정리 실패 (cutoff=$cutoff)" }
        }
    }

    companion object {
        private val CHAT_TTL: Duration = Duration.ofDays(7)
    }
}
