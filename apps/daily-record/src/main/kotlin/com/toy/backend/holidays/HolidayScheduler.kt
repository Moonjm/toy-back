package com.toy.backend.holidays

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.concurrent.ExecutorService

private val log = KotlinLogging.logger {}

@Component
class HolidayScheduler(
    private val service: HolidayService,
    private val properties: HolidayApiProperties,
    private val virtualThreadExecutor: ExecutorService,
) {
    @Scheduled(cron = "0 0 3 * * *")
    fun updateHolidays() {
        if (properties.serviceKey.isBlank()) return

        val currentYear = LocalDate.now().year
        log.info { "공휴일 데이터 업데이트: $currentYear, ${currentYear + 1}" }
        listOf(currentYear, currentYear + 1).forEach { year ->
            virtualThreadExecutor.submit {
                runCatching { service.fetchAndSaveHolidays(year) }
                    .onFailure { log.error(it) { "공휴일 업데이트 실패: year=$year" } }
            }
        }
    }
}
