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
        val today = LocalDate.now()
        val dueRuleIds =
            runCatching { service.findDueRuleIds(today) }
                .onFailure { e -> log.error(e) { "반복 규칙 조회 실패" } }
                .getOrDefault(emptyList())
        dueRuleIds.forEach { ruleId ->
            runCatching { service.generateForRule(ruleId, today) }
                .onFailure { e -> log.error(e) { "반복 내역 생성 실패: ruleId=$ruleId" } }
        }
    }
}
