package com.toy.backend.ledger.recurring

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate

private val log = KotlinLogging.logger {}

@Component
class RecurringScheduler(
    private val service: RecurringRuleService,
) {
    @Scheduled(cron = "0 30 0 * * *")
    fun generate() {
        runCatching { service.generateDueEntries(LocalDate.now()) }
            .onFailure { e -> log.error(e) { "반복 내역 생성 실패" } }
    }
}
